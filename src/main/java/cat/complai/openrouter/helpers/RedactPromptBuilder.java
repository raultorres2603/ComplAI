package cat.complai.openrouter.helpers;

import cat.complai.openrouter.dto.ComplainantIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds AI prompts for the complaint letter (redact) flow.
 * Extracted as a singleton so that both {@code OpenRouterServices} (API Lambda)
 * and {@code RedactWorkerHandler} (worker Lambda) can share the same prompt
 * logic
 * without duplication.
 *
 * <p>
 * All methods are stateless with respect to per-request data; the singleton
 * lifecycle is safe.
 */
@Singleton
public class RedactPromptBuilder {

    private static final Logger logger = Logger.getLogger(RedactPromptBuilder.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MAPPING_RESOURCE_PATTERN = "/scrapers/procedures-mapping-%s.json";
    private static final String PATH_FORMAT_RULES = "/prompt-format-rules.properties";

    // Cached format rules loaded from properties file
    private static final Map<String, String> FORMAT_RULES = loadFormatRules();

    // Cached per city — classpath reads are cheap but there is no reason to repeat
    // them.
    private static final ConcurrentHashMap<String, CityConfig> CITY_CONFIG_CACHE = new ConcurrentHashMap<>();

    /**
     * Holds the city-specific display name and optional source URLs used in the
     * system prompt.
     * Loaded once per city from the scraper mapping JSON on the classpath.
     */
    record CityConfig(String cityName, List<String> officialSources, List<String> newsSources) {
        /** Fallback used when no mapping file exists for a given cityId. */
        static CityConfig fallback(String cityId) {
            return new CityConfig(cityId, Collections.emptyList(), Collections.emptyList());
        }
    }

    /**
     * Loads format rules from the classpath prompt-format-rules.properties file.
     * These rules are used in the multilingual system prompt to provide HTML
     * formatting
     * examples and reduce token count by consolidating format instructions.
     */
    private static Map<String, String> loadFormatRules() {
        try (InputStream is = RedactPromptBuilder.class.getResourceAsStream(PATH_FORMAT_RULES)) {
            if (is == null) {
                logger.warning("Format rules file not found at " + PATH_FORMAT_RULES + "; using empty rules");
                return new HashMap<>();
            }
            Properties props = new Properties();
            props.load(is);
            Map<String, String> rulesMap = new HashMap<>();
            for (String key : props.stringPropertyNames()) {
                rulesMap.put(key, props.getProperty(key));
            }
            logger.fine("Loaded " + rulesMap.size() + " format rules from " + PATH_FORMAT_RULES);
            return Collections.unmodifiableMap(rulesMap);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to load format rules from " + PATH_FORMAT_RULES + ": " + e.getMessage(),
                    e);
            return new HashMap<>();
        }
    }

    private final ProcedureRagHelperRegistry ragRegistry;

    @Inject
    public RedactPromptBuilder(ProcedureRagHelperRegistry ragRegistry) {
        this.ragRegistry = ragRegistry;
    }

    // Test-only no-arg constructor. Creates a registry with an empty cache; it
    // loads
    // procedures-<cityId>.json from the classpath on first use, which works in
    // tests
    // because the classpath resource is present. Public so test classes in any
    // package
    // can instantiate it without a full DI context.
    public RedactPromptBuilder() {
        this(new ProcedureRagHelperRegistry());
    }

    // -------------------------------------------------------------------------
    // City config resolution
    // -------------------------------------------------------------------------

    /**
     * Returns the display name for the given cityId.
     * Reads from the scraper mapping JSON on first access; caches the result.
     * Falls back to the cityId itself when no mapping file is present.
     *
     * <p>
     * Public so that service classes in other packages (e.g.
     * {@code OpenRouterServices})
     * can resolve the city name for log messages and error responses without
     * depending on
     * the internal {@code CityConfig} type.
     */
    public static String resolveCityDisplayName(String cityId) {
        return resolveCityConfig(cityId).cityName();
    }

    /**
     * Returns the city config for the given cityId, loading it on first access.
     * Falls back to a minimal config (cityId as display name, no source URLs) when
     * the mapping file is absent so the prompt builder never throws.
     */
    // Package-private for testing.
    static CityConfig resolveCityConfig(String cityId) {
        return CITY_CONFIG_CACHE.computeIfAbsent(cityId, RedactPromptBuilder::loadCityConfig);
    }

    private static CityConfig loadCityConfig(String cityId) {
        String resourcePath = String.format(MAPPING_RESOURCE_PATTERN, cityId);
        try (InputStream is = RedactPromptBuilder.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                logger.fine(() -> "No mapping file found for city='" + cityId
                        + "'; using cityId as display name with no source URLs");
                return CityConfig.fallback(cityId);
            }
            JsonNode root = MAPPER.readTree(is);

            String cityName = root.path("cityName").asText(cityId);

            List<String> officialSources = readStringList(root, "officialSources");
            List<String> newsSources = readStringList(root, "newsSources");

            logger.fine(() -> "Loaded CityConfig for city='" + cityId + "' displayName='" + cityName + "'");
            return new CityConfig(cityName, officialSources, newsSources);
        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Failed to load city config for city='" + cityId + "'; using fallback: " + e.getMessage(), e);
            return CityConfig.fallback(cityId);
        }
    }

    private static List<String> readStringList(JsonNode root, String fieldName) {
        JsonNode node = root.path(fieldName);
        if (!node.isArray())
            return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText(null);
            if (value != null && !value.isBlank())
                result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    // -------------------------------------------------------------------------
    // Prompt construction
    // -------------------------------------------------------------------------

    /**
     * Returns the multilingual system message scoped to the given city.
     * The AI is instructed to only answer questions about that city and to draft
     * letters addressed to its City Hall.
     * 
     * Uses consolidated format rules to reduce token count while preserving all
     * required content.
     */
    public String getSystemMessage(String cityId) {
        CityConfig cfg = resolveCityConfig(cityId);
        return buildCatalanBlock(cfg) + buildSpanishBlock(cfg) + buildEnglishBlock(cfg);
    }

    /**
     * Returns a mono-lingual system message for the given city and language code.
     * If {@code language} is null or not one of {@code "CA"}, {@code "ES"},
     * {@code "EN"}, or {@code "FR"},
     * falls back to the full tri-lingual message.
     *
     * @param cityId   city identifier
     * @param language ISO language code: "CA", "ES", "EN", or "FR"
     */
    public String getSystemMessage(String cityId, String language) {
        if (language == null) {
            return getSystemMessage(cityId);
        }
        CityConfig cfg = resolveCityConfig(cityId);
        return switch (language) {
            case "CA" -> buildCatalanBlock(cfg);
            case "ES" -> buildSpanishBlock(cfg);
            case "EN" -> buildEnglishBlock(cfg);
            case "FR" -> buildFrenchBlock(cfg);
            default -> getSystemMessage(cityId);
        };
    }

    private String buildCatalanBlock(CityConfig cfg) {
        String cityName = cfg.cityName();
        String officialCat = formatSources(cfg.officialSources(), " i ", " i ");
        String indepCat = formatSources(cfg.newsSources(), ", ", "");
        String boldHtml = FORMAT_RULES.getOrDefault("format.bold.html", "<strong>{text}</strong>");
        String linkHtml = FORMAT_RULES.getOrDefault("format.link.html",
                "<a href=\"{url}\" target=\"_blank\" rel=\"noopener noreferrer\">{text}</a>");
        String listHtml = FORMAT_RULES.getOrDefault("format.list.html", "<ul><li>{item}</li></ul>");
        return String.format(
                """
                        Ets un assistent que es diu Gall Potablava, amable i proper per als veïns de %s. Ajudes a redactar cartes i queixes clares i civils adreçades a l'Ajuntament i ofereixes informació pràctica i local de %s.

                        Les teves respostes es mostraran en una aplicació web. OBLIGATORI: usa EXCLUSIVAMENT format HTML. Mai Markdown.
                        FORMAT OBLIGATORI: %s per negreta, %s per enllaços, %s per llistes.
                        URLS: NO inventes mai cap URL. Únicament pots usar URLs que apareguin explícitament al context proporcionat de procediments, esdeveniments o noticies. Si un element no té URL al context, cita'l pel nom sense cap enllaç. Les fonts oficials només són per a informació general, no per enllaços específics de tràmits.
                        • Dóna respostes detallades i completes. Quan hi hagi procediments, esdeveniments o noticies relacionades, cita'ls pel nom i INCLOU SEMPRE l'enllaç HTML a la resposta si el context en proporciona un. És OBLIGATORI incloure l'URL quan el context el faciliti.
                        • Si et demanen esdeveniments però no hi ha cap finestra temporal (data, setmana, mes o rang), demana primer aquest interval abans de donar resultats.
                        • Estructura la resposta de manera clara: explicació, passos si escau, i finalment enllaços útils.
                        • Sigues respectuós i proper, com un veí que vol ajudar de debò.
                        • Si la consulta no és sobre %s, digues-ho educadament i suggereix preguntes sobre assumptes locals.

                        Pàgines oficials: %s. Font independent: %s.

                        """,
                cityName, cityName, boldHtml, linkHtml, listHtml, cityName, officialCat, indepCat);
    }

    private String buildSpanishBlock(CityConfig cfg) {
        String cityName = cfg.cityName();
        String officialEs = formatSources(cfg.officialSources(), " y ", " y ");
        String indepEs = formatSources(cfg.newsSources(), ", ", "");
        String boldHtml = FORMAT_RULES.getOrDefault("format.bold.html", "<strong>{text}</strong>");
        String linkHtml = FORMAT_RULES.getOrDefault("format.link.html",
                "<a href=\"{url}\" target=\"_blank\" rel=\"noopener noreferrer\">{text}</a>");
        String listHtml = FORMAT_RULES.getOrDefault("format.list.html", "<ul><li>{item}</li></ul>");
        return String.format(
                """
                        En español: Eres un asistente que se llama Gall Potablava, amable y cercano para los vecinos de %s.

                        Las respuestas se mostrarán en una aplicación web. OBLIGATORIO: usa EXCLUSIVAMENTE formato HTML. Nunca Markdown.
                        FORMATO OBLIGATORIO: %s para negrita, %s para enlaces, %s para listas.
                        URLS: NUNCA inventes una URL. Solo puedes usar URLs que aparezcan explícitamente en el contexto proporcionado de procedimientos, eventos o noticias. Si un elemento no tiene URL en el contexto, cítalo por su nombre sin ningún enlace. Las fuentes oficiales son solo para información general, no para enlaces específicos.
                        • Da respuestas detalladas y completas. Cuando haya procedimientos, eventos o noticias relacionados, cítalos por su nombre e incluye SIEMPRE el enlace HTML en la respuesta si el contexto lo proporciona. Es OBLIGATORIO incluir la URL cuando el contexto la facilite.
                        • Si te preguntan por eventos pero no hay ventana temporal (fecha, semana, mes o rango), primero pide ese intervalo antes de dar resultados.
                        • Estructura la respuesta con claridad: explicación, pasos si procede, y finalmente enlaces útiles.
                        • Sé respetuoso y cercano.
                        • Si la consulta no trata sobre %s, dilo educadamente y sugiere preguntas sobre asuntos locales.

                        Páginas oficiales: %s. Fuente independiente: %s.

                        """,
                cityName, boldHtml, linkHtml, listHtml, cityName, officialEs, indepEs);
    }

    private String buildEnglishBlock(CityConfig cfg) {
        String cityName = cfg.cityName();
        String officialEn = formatSources(cfg.officialSources(), " and ", " and ");
        String indepEn = formatSources(cfg.newsSources(), ", ", "");
        String boldHtml = FORMAT_RULES.getOrDefault("format.bold.html", "<strong>{text}</strong>");
        String linkHtml = FORMAT_RULES.getOrDefault("format.link.html",
                "<a href=\"{url}\" target=\"_blank\" rel=\"noopener noreferrer\">{text}</a>");
        String listHtml = FORMAT_RULES.getOrDefault("format.list.html", "<ul><li>{item}</li></ul>");
        return String.format(
                """
                        In English (support): You are a friendly local assistant named Gall Potablava for residents of %s.

                        Responses will be displayed in a web app. IMPORTANT: use ONLY HTML formatting. Never Markdown.
                        REQUIRED: %s for bold, %s for links, %s for lists.
                        URLS: NEVER invent a URL. Only use URLs that appear explicitly in the provided context for procedures, events, or news. If an item has no URL in the context, mention its name only and do not add any link. Official sources are only for general information, not for specific links.
                        • Give detailed, complete answers. When there are related procedures, events, or news items, name them and ALWAYS include the HTML link in the response if the context provides one. Including the URL is MANDATORY when the context contains it.
                        • If asked about events but no timeframe is provided (date, week, month, or range), ask for that date window before giving results.
                        • Structure your response clearly: explanation first, then steps if applicable, then useful links.
                        • Be respectful and approachable.
                        • If the request is not about %s, politely say you can't help with that and suggest they ask about local matters.

                        Official information: %s. Independent news: %s.
                        """,
                cityName, boldHtml, linkHtml, listHtml, cityName, officialEn, indepEn);
    }

    private String buildFrenchBlock(CityConfig cfg) {
        String cityName = cfg.cityName();
        String officialFr = formatSources(cfg.officialSources(), " et ", " et ");
        String indepFr = formatSources(cfg.newsSources(), ", ", "");
        String boldHtml = FORMAT_RULES.getOrDefault("format.bold.html", "<strong>{text}</strong>");
        String linkHtml = FORMAT_RULES.getOrDefault("format.link.html",
                "<a href=\"{url}\" target=\"_blank\" rel=\"noopener noreferrer\">{text}</a>");
        String listHtml = FORMAT_RULES.getOrDefault("format.list.html", "<ul><li>{item}</li></ul>");
        return String.format(
                """
                        En français: Vous êtes un assistant local amical nommé Gall Potablava pour les résidents de %s.

                        Les réponses s'affichent dans une application web. OBLIGATOIRE: utilisez UNIQUEMENT le format HTML. Jamais Markdown.
                        FORMAT OBLIGATOIRE: %s pour le gras, %s pour les liens, %s pour les listes.
                        URLS: JAMAIS inventer une URL. Utilisez uniquement les URLs qui apparaissent explicitement dans le contexte fourni pour les procédures, événements ou informations. Si un élément n'a pas d'URL dans le contexte, mentionnez simplement son nom sans lien. Les sources officielles sont pour l'information générale uniquement.
                        • Fournissez des réponses détaillées et complètes. Quand il y a des procédures, événements ou informations connexes, nommez-les et INCLUEZ TOUJOURS le lien HTML dans la réponse si le contexte le fournit. Inclure l'URL est OBLIGATOIRE quand le contexte la contient.
                        • Si on vous demande des événements mais aucune période n'est fournie (date, semaine, mois ou plage), demandez d'abord cette plage de dates avant de donner les résultats.
                        • Structurez votre réponse clairement: explication d'abord, puis étapes si applicable, puis liens utiles.
                        • Soyez respectueux et approchable.
                        • Si la demande ne concerne pas %s, dites-le poliment et suggérez qu'ils posent des questions sur des sujets locaux.

                        Informations officielles: %s. Actualités indépendantes: %s.
                        """,
                cityName, boldHtml, linkHtml, listHtml, cityName, officialFr, indepFr);
    }

    /**
     * Queries the in-memory Lucene index for procedures relevant to the given text,
     * using the city-specific procedures loaded for the caller's JWT city claim.
     * Returns {@code null} when no matches are found or the index fails to load.
     */
    public String buildProcedureContextBlock(String query, String cityId) {
        ProcedureRagHelper helper;
        try {
            helper = ragRegistry.getForCity(cityId);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to get ProcedureRagHelper for city=" + cityId
                    + "; skipping RAG context: " + e.getMessage(), e);
            return null;
        }
        List<ProcedureRagHelper.Procedure> matches = helper.search(query);
        return buildProcedureContextBlockFromMatches(matches, cityId);
    }

    public String buildProcedureContextBlockFromMatches(List<ProcedureRagHelper.Procedure> matches, String cityId) {
        if (matches == null || matches.isEmpty())
            return null;

        String cityName = resolveCityConfig(cityId).cityName();
        StringBuilder sb = new StringBuilder();
        sb.append("CONTEXT FROM MUNICIPAL PROCEDURES:\n\n");
        for (ProcedureRagHelper.Procedure p : matches) {
            sb.append("---\n");
            sb.append("**").append(p.title).append("**\n");
            // Prefer HTML anchor tags in the RAG context and ensure they open in a new tab
            if (p.url != null && !p.url.isBlank()) {
                sb.append("<a href=\"").append(p.url)
                        .append("\" target=\"_blank\" rel=\"noopener noreferrer\">Més informació / Más información / More information</a>\n\n");
            }
            if (!p.description.isBlank())
                sb.append(p.description).append("\n\n");
            if (!p.requirements.isBlank()) {
                sb.append("**Requirements:**\n");
                for (String req : p.requirements.split("\\n")) {
                    if (!req.isBlank())
                        sb.append("- ").append(req.trim()).append("\n");
                }
                sb.append("\n");
            }
            if (!p.steps.isBlank()) {
                sb.append("**Steps:**\n");
                for (String step : p.steps.split("\\n")) {
                    if (!step.isBlank())
                        sb.append("- ").append(step.trim()).append("\n");
                }
                sb.append("\n");
            }
            sb.append("---\n\n");
        }
        // Explicit instruction: the AI must use the context and include the links.
        // "May be relevant" language was too weak — the AI ignored the links.
        sb.append(
                """
                        INSTRUCTIONS:
                        - Use the procedure information above to answer the question.
                        - MANDATORY: If a procedure listed above includes a URL, you MUST include that HTML link in your response. Do not omit it.
                        - NEVER invent URLs. Only use URLs explicitly listed above. Use plain text names if no URL exists.
                        - Format requirements/steps as HTML lists (<ul><li>).
                        - If context doesn't cover the question, use general knowledge about \
                        """)
                .append(cityName).append(", but do not invent procedure links.");
        return sb.toString();
    }

    /**
     * Builds the redact prompt when the complainant's identity is fully known.
     * Today's date is injected so the AI does not leave a placeholder. The prompt
     * explicitly forbids placeholder fields and follow-up questions: the goal is a
     * final, complete, ready-to-submit letter with no blanks for the user to fill
     * in.
     *
     * <p>
     * Only name, surname and ID are mandatory. Address, phone and other contact
     * details are optional: the AI must include them if the user mentioned them
     * anywhere in the complaint text, and silently omit them otherwise — never use
     * a placeholder.
     */
    public String buildRedactPromptWithIdentity(String complaint, ComplainantIdentity identity, String cityId) {
        String cityName = resolveCityConfig(cityId).cityName();
        String today = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("ca")));
        return String.format(
                """
                        PRE-CHECK: Is this a complaint for Ajuntament de %s?

                        IF NOT (question, greeting, other municipality, etc.):
                        -> Respond politely in user's language.
                        -> Explain this service is only for formal complaint letters to Ajuntament de %s.
                        -> Invite them to describe their complaint.
                        -> NO JSON header, NO letter.

                        IF YES, continue:

                        JSON HEADER (line 1): {"format": "pdf"}
                        Then blank line, then letter.

                        TASK: Write formal complaint letter to Ajuntament de %s.

                        RULES:
                        1. Date: "%s" (no placeholders)
                        2. Always include: %s %s, ID: %s
                        3. Optional: address/phone/email ONLY if mentioned
                        4. NEVER invent data or use placeholders
                        5. NO follow-up questions or extra comments
                        6. Complete, ready-to-submit letter
                        7. If not about %s, politely decline
                        8. PLAIN TEXT output (no Markdown)

                        Complaint: %s""",
                cityName, // PRE-CHECK first mention
                cityName, // PRE-CHECK second mention
                cityName, // Task: addressed to the Ajuntament de ...
                today,
                identity.name().trim(),
                identity.surname().trim(),
                identity.idNumber().trim(),
                cityName, // Rule 7: if not about this city
                complaint.trim());
    }

    /**
     * Builds the redact prompt when one or more mandatory identity fields are
     * missing.
     * The AI is instructed to ask the user only for the three mandatory fields
     * (name,
     * surname, ID). Address, phone and other contact details are optional and must
     * never
     * be requested.
     */
    public String buildRedactPromptRequestingIdentity(String complaint, ComplainantIdentity partialIdentity,
            String cityId) {
        String cityName = resolveCityConfig(cityId).cityName();
        StringBuilder missing = new StringBuilder();
        boolean hasName = partialIdentity != null && partialIdentity.name() != null
                && !partialIdentity.name().isBlank();
        boolean hasSurname = partialIdentity != null && partialIdentity.surname() != null
                && !partialIdentity.surname().isBlank();
        boolean hasId = partialIdentity != null && partialIdentity.idNumber() != null
                && !partialIdentity.idNumber().isBlank();

        if (!hasName)
            missing.append("- First name (nom)\n");
        if (!hasSurname)
            missing.append("- Surname (cognoms)\n");
        if (!hasId)
            missing.append("- ID/DNI/NIF number\n");

        String today = LocalDate.now()
                .format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("ca")));

        return String.format(
                """
                        Service: Formal complaint letters for Ajuntament de %s.
                        Status: Missing mandatory identity fields.

                        TASK: Determine scenario:

                        SCENARIO 0: NOT a complaint about %s (greeting, question, other municipality, etc.)
                        -> Respond politely in user's language
                        -> Explain service is only for complaint letters to Ajuntament de %s
                        -> Invite them to describe their complaint
                        -> NO JSON header, NO identity request

                        SCENARIO A: IS complaint AND contains all missing fields (e.g. "My name is John Doe, ID 12345")
                        Missing: %s
                        -> ACTION: Extract identity and DRAFT LETTER immediately
                        -> JSON header: {"format": "pdf"}
                        -> Rules: Date "%s", include name/ID, address/phone ONLY if mentioned, NO placeholders, NO follow-ups
                        -> Plain text letter body after JSON header

                        SCENARIO B: IS complaint BUT missing fields
                        Missing: %s
                        -> ACTION: Politely request missing information
                        -> NO letter draft, NO JSON header
                        -> Respond in user's language

                        User message: %s""",
                cityName,
                cityName,
                cityName,
                missing.toString().trim(),
                today,
                missing.toString().trim(),
                complaint.trim());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String formatSources(List<String> sources, String sep, String lastSep) {
        if (sources == null || sources.isEmpty())
            return "";
        if (sources.size() == 1)
            return sources.getFirst();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0)
                sb.append(i == sources.size() - 1 ? lastSep : sep);
            sb.append(sources.get(i));
        }
        return sb.toString();
    }
}
