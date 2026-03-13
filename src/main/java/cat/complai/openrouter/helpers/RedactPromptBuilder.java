package cat.complai.openrouter.helpers;

import cat.complai.openrouter.dto.ComplainantIdentity;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
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

    // Official and independent sources kept here so only this class needs updating
    // if URLs ever change.
    private static final List<String> OFFICIAL_SOURCES = List.of(
            "https://www.elprat.cat/",
            "https://www.pratespais.com/"
    );
    private static final List<String> INDEPENDENT_SOURCES = List.of(
            "https://www.instagram.com/elprataldia/"
    );

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

    /**
     * Returns the multilingual system message that defines the assistant persona,
     * scope, and source references.
     */
    public String getSystemMessage() {
        String officialCat = formatSources(OFFICIAL_SOURCES, " i ", " i ");
        String officialEs  = formatSources(OFFICIAL_SOURCES, " y ", " y ");
        String officialEn  = formatSources(OFFICIAL_SOURCES, " and ", " and ");
        String indepCat    = formatSources(INDEPENDENT_SOURCES, ", ", "");
        String indepEs     = formatSources(INDEPENDENT_SOURCES, ", ", "");
        String indepEn     = formatSources(INDEPENDENT_SOURCES, ", ", "");
        return String.format("""
                Ets un assistent que es diu Gall Potablava, amable i proper per als veïns d'El Prat de Llobregat. Ajudes a redactar cartes i queixes clares i civils adreçades a l'Ajuntament i ofereixes informació pràctica i local d'El Prat. Mantén les respostes curtes, respectuoses i fàcils de llegir, com un veí que vol ajudar. Si la consulta no és sobre El Prat de Llobregat, digues-ho educadament i explica que no pots ajudar amb aquesta petició; també pots suggerir que facin una pregunta sobre assumptes locals.

Pàgines oficials d'informació: %s
Font independent de notícies locals: %s

En español: Eres un asistente que se llama Gall Potablava amable y cercano para los vecinos de El Prat de Llobregat. Ayuda a redactar cartes i queixes dirigides al Ayuntamiento i ofereix informació pràctica i local. Mantén las respuestas cortas y fáciles de entender. Si la consulta no trata sobre El Prat, dilo educadamente y sugiere que pregunten sobre asuntos locales.

Páginas oficiales de información: %s
Fuente independiente de noticias locales: %s

In English (support): You are a friendly local assistant named Gall Potablava for residents of El Prat de Llobregat. Help draft clear, civil letters to the City Hall and provide practical local information. Keep answers short and easy to read. If the request is not about El Prat de Llobregat, politely say you can't help with that request.

Official information sources: %s
Independent local news source: %s
""", officialCat, indepCat, officialEs, indepEs, officialEn, indepEn);
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
        if (matches.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("CONTEXT FROM PRAT ESPAIS PROCEDURES:\n\n");
        for (ProcedureRagHelper.Procedure p : matches) {
            sb.append("---\n");
            sb.append("**").append(p.title).append("**\n");
            sb.append("[More information] (").append(p.url).append(")\n\n");
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
        sb.append("Use this context to answer the user's question. If the context is relevant, cite the procedure name and provide the source link. If the context does not help, answer based on your general knowledge about El Prat.");
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
    public String buildRedactPromptWithIdentity(String complaint, ComplainantIdentity identity) {
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("ca")));
        return String.format(
                """
                        IMPORTANT: Your response MUST start with a single-line JSON header on line 1.
                        No text, no markdown, no explanation before it.
                        The header must be exactly: {"format": "pdf"}
                        After that JSON header, leave one blank line, then write the letter body.

                        Task: Write a formal complaint letter addressed to the Ajuntament d'El Prat de Llobregat.

                        Rules you MUST follow — no exceptions:
                        1. Use specifically this date: "%s". Do NOT use placeholders like [Data] or [Date].
                        2. Mandatory complainant data — always include these in the letter header and signature:
                           - Full name: %s %s
                           - ID/DNI/NIF: %s
                        3. Optional complainant data — include ONLY if the user mentioned them in the complaint text below. If they are not mentioned, omit them entirely:
                           - Address, phone number, email, or any other contact detail.
                        4. NEVER invent data. NEVER use bracket placeholders like [address], [phone], [your data here], or anything similar. If a field is not provided, leave it out completely.
                        5. Do NOT ask any follow-up questions. Do NOT add "What do you think?", "Would you like to add anything?", notes, suggestions or tips after the letter. The letter is final as-is.
                        6. Write a complete, ready-to-submit letter.
                        7. If the complaint is not about El Prat de Llobregat, politely say you can't help.
                        8. Output the letter body as PLAIN TEXT. Do NOT use Markdown formatting (like **, __, #).

                        Complaint text (may contain optional contact details to include):
                        %s""",
                today,
                identity.name().trim(),
                identity.surname().trim(),
                identity.idNumber().trim(),
                complaint.trim()
        );
    }

    /**
     * Builds the redact prompt when one or more mandatory identity fields are missing.
     * The AI is instructed to ask the user only for the three mandatory fields (name,
     * surname, ID). Address, phone and other contact details are optional and must never
     * be requested.
     */
    public String buildRedactPromptRequestingIdentity(String complaint, ComplainantIdentity partialIdentity) {
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
                        The user wants to draft a formal complaint addressed to the Ajuntament of El Prat de Llobregat.

                        Current status: Missing mandatory identity fields.

                        YOUR TASK:
                        1. Analyze the complaint text below.
                        2. Check if the user has provided the following missing fields in the text:
                        %s

                        SCENARIO A: The text DOES contain ALL the missing fields (e.g. they wrote "My name is John Doe, ID 12345").
                           -> ACTION: Extract the identity and DRAFT THE LETTER immediately.
                           -> You MUST start with the JSON header on line 1: {"format": "pdf"}
                           -> Follow these drafting rules:
                              1. Use date: "%s".
                              2. Include the extracted Full Name and ID/DNI/NIF in the header and signature.
                              3. Include address/phone ONLY if mentioned.
                              4. NO placeholders. NO follow-up questions.
                              5. COMPLAINT BODY: Write a clear, formal complaint based on the user's text.
                              6. Output the letter body as PLAIN TEXT after the JSON header.

                        SCENARIO B: The text DOES NOT contain all missing fields.
                           -> ACTION: Ask the user politely for the missing information.
                           -> Do NOT draft the letter yet.
                           -> Do NOT output the JSON header.
                           -> Simply ask for:
                           %s
                           -> Respond in the same language the user is using.

                        Complaint text:
                        %s""",
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
        if (sources.isEmpty()) return "";
        if (sources.size() == 1) return sources.getFirst();
        StringJoiner joiner = new StringJoiner(sep);
        for (int i = 0; i < sources.size() - 1; i++) joiner.add(sources.get(i));
        return joiner + lastSep + sources.getLast();
    }
}

