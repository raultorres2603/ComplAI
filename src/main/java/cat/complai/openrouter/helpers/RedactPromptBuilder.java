package cat.complai.openrouter.helpers;

import cat.complai.openrouter.dto.ComplainantIdentity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds AI prompts for the complaint letter (redact) flow.
 * Extracted as a singleton so that both {@code OpenRouterServices} (API Lambda)
 * and {@code RedactWorkerHandler} (worker Lambda) can share the same prompt logic
 * without duplication.
 *
 * <p>All methods are stateless with respect to per-request data; the singleton
 * lifecycle is safe.
 */
@Singleton
public class RedactPromptBuilder {

    private static final Logger logger = Logger.getLogger(RedactPromptBuilder.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MAPPING_RESOURCE_PATTERN = "/scrapers/procedures-mapping-%s.json";

    // Cached per city — classpath reads are cheap but there is no reason to repeat them.
    private static final ConcurrentHashMap<String, CityConfig> CITY_CONFIG_CACHE = new ConcurrentHashMap<>();

    /**
     * Holds the city-specific display name and optional source URLs used in the system prompt.
     * Loaded once per city from the scraper mapping JSON on the classpath.
     */
    record CityConfig(String cityName, List<String> officialSources, List<String> newsSources) {
        /** Fallback used when no mapping file exists for a given cityId. */
        static CityConfig fallback(String cityId) {
            return new CityConfig(cityId, Collections.emptyList(), Collections.emptyList());
        }
    }

    private final ProcedureRagHelperRegistry ragRegistry;

    @Inject
    public RedactPromptBuilder(ProcedureRagHelperRegistry ragRegistry) {
        this.ragRegistry = ragRegistry;
    }

    // Test-only no-arg constructor. Creates a registry with an empty cache; it loads
    // procedures-<cityId>.json from the classpath on first use, which works in tests
    // because the classpath resource is present. Public so test classes in any package
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
     * <p>Public so that service classes in other packages (e.g. {@code OpenRouterServices})
     * can resolve the city name for log messages and error responses without depending on
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
        if (!node.isArray()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText(null);
            if (value != null && !value.isBlank()) result.add(value);
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
     */
    public String getSystemMessage(String cityId) {
        CityConfig cfg = resolveCityConfig(cityId);
        String cityName = cfg.cityName();

        String officialCat = formatSources(cfg.officialSources(), " i ", " i ");
        String officialEs  = formatSources(cfg.officialSources(), " y ", " y ");
        String officialEn  = formatSources(cfg.officialSources(), " and ", " and ");
        String indepCat    = formatSources(cfg.newsSources(), ", ", "");
        String indepEs     = formatSources(cfg.newsSources(), ", ", "");
        String indepEn     = formatSources(cfg.newsSources(), ", ", "");

        return String.format("""
                Ets un assistent que es diu Gall Potablava, amable i proper per als veïns de %s. Ajudes a redactar cartes i queixes clares i civils adreçades a l'Ajuntament i ofereixes informació pràctica i local de %s.

Les teves respostes es mostraran en una aplicació web. OBLIGATORI: usa EXCLUSIVAMENT format HTML. Mai Markdown.
FORMAT PROHIBIT (no usar mai): **negreta**, __subratllat__, - llista amb guió, * llista amb asterisc, [text](URL).
FORMAT OBLIGATORI: <strong>negreta</strong>, <ul><li>element de llista</li></ul>, <a href="URL" target="_blank" rel="noopener noreferrer">text</a>, <p>paràgraf</p>.
URLS: NO inventes mai cap URL. Únicament pots usar URLs que apareguin explícitament al context proporcionat sobre procediments. Si un tràmit no té URL al context, cita'l pel nom sense cap enllaç. Les fonts oficials generals (com el portal de l'ajuntament) només són per a informació general, no per enllaços específics de tràmits.
- Dóna respostes detallades i completes. No tallies la informació si és rellevant per a l'usuari.
        - Quan hi hagi tràmits o procediments municipals relacionats amb la consulta, cita'ls pel nom i, si el context en proporciona un URL, inclou l'enllaç directe.
- Estructura la resposta de manera clara: primer l'explicació, després els passos o requisits si escau, i finalment els enllaços útils.
- Sigues respectuós i proper, com un veí que vol ajudar de debò.
- Si la consulta no és sobre %s, digues-ho educadament i suggereix que facin una pregunta sobre assumptes locals.

Pàgines oficials d'informació: %s
Font independent de notícies locals: %s

En español: Eres un asistente que se llama Gall Potablava, amable y cercano para los vecinos de %s.

Las respuestas se mostrarán en una aplicación web. OBLIGATORIO: usa EXCLUSIVAMENTE formato HTML. Nunca Markdown.
FORMATO PROHIBIDO (no usar nunca): **negrita**, __subrayado__, - lista con guión, * lista con asterisco, [texto](URL).
FORMATO OBLIGATORIO: <strong>negrita</strong>, <ul><li>elemento de lista</li></ul>, <a href="URL" target="_blank" rel="noopener noreferrer">texto</a>, <p>párrafo</p>.
URLS: NUNCA inventes una URL. Solo puedes usar URLs que aparezcan explícitamente en el contexto proporcionado sobre procedimientos. Si un trámite no tiene URL en el contexto, cítalo por su nombre sin ningún enlace. Las fuentes oficiales generales (como el portal del ayuntamiento) son solo para información general, no para enlaces específicos de trámites.
- Da respuestas detalladas y completas. No cortes la información si es relevante para el usuario.
        - Cuando haya trámites o procedimientos municipales relacionados con la consulta, cítalos por su nombre e incluye el enlace directo solo si el contexto lo proporciona.
- Estructura la respuesta con claridad: primero la explicación, luego los pasos o requisitos si procede, y finalmente los enlaces útiles.
- Sé respetuoso y cercano.
- Si la consulta no trata sobre %s, dilo educadamente y sugiere que pregunten sobre asuntos locales.

Páginas oficiales de información: %s
Fuente independiente de noticias locales: %s

In English (support): You are a friendly local assistant named Gall Potablava for residents of %s.

Responses will be displayed in a web app. IMPORTANT: use ONLY HTML formatting. Never Markdown.
FORBIDDEN (never use): **bold**, __underline__, - bullet with dash, * bullet with asterisk, [text](URL).
REQUIRED: <strong>bold</strong>, <ul><li>list item</li></ul>, <a href="URL" target="_blank" rel="noopener noreferrer">link text</a>, <p>paragraph</p>.
URLS: NEVER invent a URL. Only use URLs that appear explicitly in the provided context about procedures. If a procedure has no URL in the context, mention its name only — do not add any link. General official sources (like the city hall portal) are only for general information, not for specific procedure links.
- Give detailed, complete answers. Do not truncate information that is relevant to the user.
- When there are municipal procedures or forms related to the query, name them and include the direct link only if the context provides one.
- Structure your response clearly: explanation first, then steps or requirements if applicable, then useful links.
- Be respectful and approachable.
- If the request is not about %s, politely say you can't help with that and suggest they ask about local matters.

Official information sources: %s
Independent local news source: %s
""",
                cityName, cityName, cityName, officialCat, indepCat,
                cityName, cityName, officialEs, indepEs,
                cityName, cityName, officialEn, indepEn);
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
        if (matches == null || matches.isEmpty()) return null;

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
            if (!p.description.isBlank()) sb.append(p.description).append("\n\n");
            if (!p.requirements.isBlank()) {
                sb.append("**Requirements:**\n");
                for (String req : p.requirements.split("\\n")) {
                    if (!req.isBlank()) sb.append("- ").append(req.trim()).append("\n");
                }
                sb.append("\n");
            }
            if (!p.steps.isBlank()) {
                sb.append("**Steps:**\n");
                for (String step : p.steps.split("\\n")) {
                    if (!step.isBlank()) sb.append("- ").append(step.trim()).append("\n");
                }
                sb.append("\n");
            }
            sb.append("---\n\n");
        }
        // Explicit instruction: the AI must use the context and include the links.
        // "May be relevant" language was too weak — the AI ignored the links.
        sb.append("""
                INSTRUCTIONS:
                - Use the procedure information above to answer the question.
                - Include HTML links ONLY if URL is explicitly provided above.
                - NEVER invent URLs. Use plain text names if no URL exists.
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
     * final, complete, ready-to-submit letter with no blanks for the user to fill in.
     *
     * <p>Only name, surname and ID are mandatory. Address, phone and other contact
     * details are optional: the AI must include them if the user mentioned them
     * anywhere in the complaint text, and silently omit them otherwise — never use
     * a placeholder.
     */
    public String buildRedactPromptWithIdentity(String complaint, ComplainantIdentity identity, String cityId) {
        String cityName = resolveCityConfig(cityId).cityName();
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("ca")));
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
                cityName,   // PRE-CHECK first mention
                cityName,   // PRE-CHECK second mention
                cityName,   // Task: addressed to the Ajuntament de ...
                today,
                identity.name().trim(),
                identity.surname().trim(),
                identity.idNumber().trim(),
                cityName,   // Rule 7: if not about this city
                complaint.trim()
        );
    }

    /**
     * Builds the redact prompt when one or more mandatory identity fields are missing.
     * The AI is instructed to ask the user only for the three mandatory fields (name,
     * surname, ID). Address, phone and other contact details are optional and must never
     * be requested.
     */
    public String buildRedactPromptRequestingIdentity(String complaint, ComplainantIdentity partialIdentity, String cityId) {
        String cityName = resolveCityConfig(cityId).cityName();
        StringBuilder missing = new StringBuilder();
        boolean hasName    = partialIdentity != null && partialIdentity.name()     != null && !partialIdentity.name().isBlank();
        boolean hasSurname = partialIdentity != null && partialIdentity.surname()  != null && !partialIdentity.surname().isBlank();
        boolean hasId      = partialIdentity != null && partialIdentity.idNumber() != null && !partialIdentity.idNumber().isBlank();

        if (!hasName)    missing.append("- First name (nom)\n");
        if (!hasSurname) missing.append("- Surname (cognoms)\n");
        if (!hasId)      missing.append("- ID/DNI/NIF number\n");

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("ca")));

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
                complaint.trim()
        );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String formatSources(List<String> sources, String sep, String lastSep) {
        if (sources == null || sources.isEmpty()) return "";
        if (sources.size() == 1) return sources.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) sb.append(i == sources.size() - 1 ? lastSep : sep);
            sb.append(sources.get(i));
        }
        return sb.toString();
    }
}

