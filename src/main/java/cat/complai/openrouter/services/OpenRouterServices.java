package cat.complai.openrouter.services;

import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micronaut.context.annotation.Value;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

// PDFBox imports

import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OutputFormat;
import cat.complai.openrouter.helpers.AiParsed;
import cat.complai.openrouter.helpers.ProcedureRagHelper;
import cat.complai.openrouter.helpers.PdfGenerator;

@Singleton
public class OpenRouterServices implements IOpenRouterService {

    private static final int MAX_HISTORY_TURNS = 10;
    private final HttpWrapper httpWrapper;
    private final Logger logger = Logger.getLogger(OpenRouterServices.class.getName());
    private final Cache<String, List<MessageEntry>> conversationCache;
    // Stores the original complaint text for conversations where identity was missing on the first
    // turn. Keyed by conversationId. Cleared once the identity is complete and the letter is drafted.
    private final Cache<String, String> pendingComplaintCache;
    private final int maxInputLength;
    private final int overallTimeoutSeconds;

    public record MessageEntry(String role, String content) {}

    private static ProcedureRagHelper procedureRagHelper;
    static {
        try {
            procedureRagHelper = new ProcedureRagHelper();
        } catch (Exception e) {
            // Log error, but allow fallback to no context
            Logger.getLogger(OpenRouterServices.class.getName()).log(Level.SEVERE, "Failed to initialize ProcedureRagHelper", e);
            procedureRagHelper = null;
        }
    }

    @Inject
    public OpenRouterServices(HttpWrapper httpWrapper, @Value("${complai.input.max-length-chars:5000}") int maxInputLength,
                             @Value("${OPENROUTER_OVERALL_TIMEOUT_SECONDS:60}") int overallTimeoutSeconds) {
        this.httpWrapper = Objects.requireNonNull(httpWrapper, "httpWrapper");
        this.conversationCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
        this.pendingComplaintCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
        this.maxInputLength = maxInputLength;
        this.overallTimeoutSeconds = (overallTimeoutSeconds > 0) ? overallTimeoutSeconds : 30; // fallback to 30s if invalid
    }

    private String buildProcedureContextBlock(String query) {
        if (procedureRagHelper == null) return null;
        List<ProcedureRagHelper.Procedure> matches = procedureRagHelper.search(query);
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

    // Official and independent sources for easy maintenance
    private static final List<String> OFFICIAL_SOURCES = List.of(
            "https://www.elprat.cat/",
            "https://www.pratespais.com/"
    );
    private static final List<String> INDEPENDENT_SOURCES = List.of(
            "https://www.instagram.com/elprataldia/"
    );

    private String formatSources(List<String> sources, String sep, String lastSep) {
        if (sources.isEmpty()) return "";
        if (sources.size() == 1) return sources.getFirst();
        StringJoiner joiner = new StringJoiner(sep);
        for (int i = 0; i < sources.size() - 1; i++) joiner.add(sources.get(i));
        return joiner + lastSep + sources.getLast();
    }

    private String getSystemMessage() {
        String officialCat = formatSources(OFFICIAL_SOURCES, " i ", " i ");
        String officialEs = formatSources(OFFICIAL_SOURCES, " y ", " y ");
        String officialEn = formatSources(OFFICIAL_SOURCES, " and ", " and ");
        String indepCat = formatSources(INDEPENDENT_SOURCES, ", ", "");
        String indepEs = formatSources(INDEPENDENT_SOURCES, ", ", "");
        String indepEn = formatSources(INDEPENDENT_SOURCES, ", ", "");
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
     * Builds the redact prompt when the complainant's identity is fully known.
     * Today's date is injected so the AI does not leave a placeholder. The prompt
     * explicitly forbids placeholder fields and follow-up questions: the goal is a
     * final, complete, ready-to-submit letter with no blanks for the user to fill in.
     *
     * Only name, surname and ID are mandatory. Address, phone and other contact details
     * are optional: the AI must include them if the user mentioned them anywhere in the
     * complaint text, and silently omit them otherwise — never use a placeholder.
     */
    private String buildRedactPromptWithIdentity(String complaint, ComplainantIdentity identity) {
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
     * The AI is instructed to ask the user only for the three mandatory fields (name, surname,
     * ID). Address, phone and other contact details are optional and must never be requested.
     */
    private String buildRedactPromptRequestingIdentity(String complaint, ComplainantIdentity partialIdentity) {
        StringBuilder missing = new StringBuilder();
        boolean hasName = partialIdentity != null && partialIdentity.name() != null && !partialIdentity.name().isBlank();
        boolean hasSurname = partialIdentity != null && partialIdentity.surname() != null && !partialIdentity.surname().isBlank();
        boolean hasId = partialIdentity != null && partialIdentity.idNumber() != null && !partialIdentity.idNumber().isBlank();

        if (!hasName) missing.append("- First name (nom)\n");
        if (!hasSurname) missing.append("- Surname (cognoms)\n");
        if (!hasId) missing.append("- ID/DNI/NIF number\n");

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

    @Override
    public OpenRouterResponseDto ask(String question, String conversationId) {
        logger.info("ask() called");
        if (question == null || question.isBlank()) {
            logger.fine("ask() rejected: empty question");
            return new OpenRouterResponseDto(false, null, "Question must not be empty.", null, OpenRouterErrorCode.VALIDATION);
        }
        if (question.length() > maxInputLength) {
            logger.fine("ask() rejected: question too long");
            return new OpenRouterResponseDto(false, null, "Question exceeds maximum allowed length (" + maxInputLength + " characters).", null, OpenRouterErrorCode.VALIDATION);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        // System message
        messages.add(Map.of("role", "system", "content", getSystemMessage()));
        // Inject procedure context if available
        String contextBlock = buildProcedureContextBlock(question);
        if (contextBlock != null) {
            messages.add(Map.of("role", "system", "content", contextBlock));
        }
        // Conversation history
        if (conversationId != null && !conversationId.isBlank()) {
            List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
            if (history != null) {
                for (MessageEntry entry : history) {
                    messages.add(Map.of("role", entry.role(), "content", entry.content()));
                }
            }
        }
        // Current user message
        messages.add(Map.of("role", "user", "content", question.trim()));

        logger.fine(() -> "ask() messages prepared: " + messages.size() + " total");
        OpenRouterResponseDto response = callOpenRouterAndExtract(messages);

        // On success, update conversation history
        if (conversationId != null && !conversationId.isBlank() && response.getMessage() != null) {
            updateConversationHistory(conversationId, question, response.getMessage());
        }
        return response;
    }

    @Override
    public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
        logger.info("redactComplaint() called");
        if (complaint == null || complaint.isBlank()) {
            logger.fine("redactComplaint() rejected: empty complaint");
            return new OpenRouterResponseDto(false, null, "Complaint must not be empty.", null, OpenRouterErrorCode.VALIDATION);
        }
        if (complaint.length() > maxInputLength) {
            logger.fine("redactComplaint() rejected: complaint too long");
            return new OpenRouterResponseDto(false, null, "Complaint exceeds maximum allowed length (" + maxInputLength + " characters).", null, OpenRouterErrorCode.VALIDATION);
        }

        // Reject explicit anonymity requests up front. The Ajuntament does not accept anonymous
        // complaints — formal letters require the complainant's full name and ID number.
        if (requestsAnonymity(complaint)) {
            logger.info("redactComplaint() rejected: user explicitly requested anonymous complaint");
            return new OpenRouterResponseDto(false, null,
                    "Anonymous complaints cannot be drafted. The Ajuntament requires full name and ID/DNI/NIF on all formal complaints.",
                    null, OpenRouterErrorCode.VALIDATION);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", getSystemMessage()));
        // Inject procedure context if available
        String contextBlock = buildProcedureContextBlock(complaint);
        if (contextBlock != null) {
            messages.add(Map.of("role", "system", "content", contextBlock));
        }
        if (conversationId != null && !conversationId.isBlank()) {
            List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
            if (history != null) {
                for (MessageEntry entry : history) {
                    messages.add(Map.of("role", entry.role(), "content", entry.content()));
                }
            }
        }

        // Choose the prompt based on whether we have a complete identity.
        // When identity is incomplete, the AI is instructed to ask for the missing fields first.
        // The caller is expected to collect the AI's question, present it to the user, and
        // re-submit with the full identity in a subsequent request.
        final String userPrompt;
        final boolean identityComplete = identity != null && identity.isComplete();
        if (identityComplete) {
            // On the second turn the user's message contains the identity data they typed (e.g.
            // "My name is Raul Torres, DNI 49872354C, I live at Carrer Major 10"). The original
            // complaint was saved on the first turn. We combine both so that any optional contact
            // details the user included in their identity reply (address, phone, etc.) are visible
            // to the prompt and can be included in the letter.
            String originalComplaint = (conversationId != null && !conversationId.isBlank())
                    ? pendingComplaintCache.getIfPresent(conversationId)
                    : null;
            if (originalComplaint != null) {
                pendingComplaintCache.invalidate(conversationId);
                logger.fine("redactComplaint() using stored original complaint for conversationId=" + conversationId);
            }
            // If there is an original complaint, prepend it so the AI has full context.
            // If identity was supplied on the very first call, use the current message as-is.
            String complaintForPrompt = (originalComplaint != null)
                    ? originalComplaint + "\n\n" + complaint
                    : complaint;
            userPrompt = buildRedactPromptWithIdentity(complaintForPrompt, identity);
        } else {
            // Save the original complaint so we can retrieve it when the user provides identity
            // on a follow-up turn. Without this, the second-turn prompt would use the identity
            // message as the complaint text, producing an incoherent letter.
            if (conversationId != null && !conversationId.isBlank()) {
                pendingComplaintCache.put(conversationId, complaint);
                logger.fine("redactComplaint() saved original complaint for conversationId=" + conversationId);
            }
            userPrompt = buildRedactPromptRequestingIdentity(complaint, identity);
        }

        messages.add(Map.of("role", "user", "content", userPrompt));

        logger.fine(() -> "redactComplaint() messages prepared: " + messages.size() + " total, identityComplete=" + identityComplete);
        OpenRouterResponseDto aiDto = callOpenRouterAndExtract(messages);

        // Propagate any non-success result (refusal, upstream error, timeout, internal error) immediately.
        // Continuing to the header-parsing logic with a failed AI response would produce incorrect
        // fallback behaviour — e.g. a refusal would silently become a 200 JSON response.
        if (aiDto.getErrorCode() != OpenRouterErrorCode.NONE) {
            return aiDto;
        }

        // On success, update conversation history
        if (conversationId != null && !conversationId.isBlank() && aiDto.getMessage() != null) {
            updateConversationHistory(conversationId, userPrompt, aiDto.getMessage());
        }

        // Parse optional AI-supplied header: either JSON first-line or simple 'FORMAT: pdf' header.
        AiParsed parsed = AiParsed.parseAiFormatHeader(aiDto.getMessage());
        OutputFormat effectiveFormat = format == null ? OutputFormat.AUTO : format;

        // If client requested AUTO and the AI supplied a format hint, promote to that format.
        // If the client was explicit (PDF or JSON), keep their choice unconditionally.
        if (effectiveFormat == OutputFormat.AUTO && parsed.format() != null && parsed.format() != OutputFormat.AUTO) {
            effectiveFormat = parsed.format();
        }

        // When identity is incomplete the AI response is normally a question asking the user
        // for the missing fields. Return it as a JSON success so the client can display the
        // question and collect the missing data.
        //
        // Exception: if effectiveFormat is PDF (either explicitly requested or promoted due to
        // successful identity extraction), we fall through to the PDF generation logic.
        if (!identityComplete && effectiveFormat != OutputFormat.PDF) {
            // Return parsed.message() to ensure we strip any potential header if present,
            // though normally in this case (Scenario B) the AI wouldn't emit one.
            return new OpenRouterResponseDto(true, parsed.message(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
        }

        // The AI did not emit the required JSON header. Log the raw response prefix to aid diagnosis.
        // Graceful fallback: use the full AI message as the letter body.
        // - PDF requested: generate the PDF from the raw message; the header was only a format hint
        //   and its absence does not make the letter content unusable.
        // - AUTO or JSON requested: return the raw AI message as a JSON response so the user still
        //   gets their letter.
        if (parsed.format() == null || parsed.format() == OutputFormat.AUTO) {
            String rawPreview = aiDto.getMessage() == null ? "<null>"
                    : (aiDto.getMessage().length() > 200 ? aiDto.getMessage().substring(0, 200) + "..." : aiDto.getMessage());
            logger.warning("AI response missing required JSON header; raw response prefix: " + rawPreview);

            if (effectiveFormat == OutputFormat.PDF) {
                String letterBody = aiDto.getMessage() == null ? "" : aiDto.getMessage();
                if (letterBody.isBlank()) {
                    return new OpenRouterResponseDto(false, null, "AI returned an empty response; cannot produce PDF.", aiDto.getStatusCode(), OpenRouterErrorCode.UPSTREAM);
                }
                try {
                    byte[] pdf = PdfGenerator.generatePdf(letterBody);
                    return new OpenRouterResponseDto(true, null, null, null, OpenRouterErrorCode.NONE, pdf);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "PDF generation failed (headerless fallback)", e);
                    return new OpenRouterResponseDto(false, null, "PDF generation failed: " + e.getMessage(), null, OpenRouterErrorCode.INTERNAL);
                }
            }
            // Fall back: return the raw AI message as a JSON response so the user gets their letter.
            return new OpenRouterResponseDto(true, aiDto.getMessage(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
        }

        boolean producePdf = effectiveFormat == OutputFormat.PDF;

        if (!producePdf) {
            // return the cleaned AI message (without header)
            return new OpenRouterResponseDto(true, parsed.message(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
        }

        // The AI emitted the JSON header but forgot to write the letter body.
        // Generating a PDF from an empty body produces a useless placeholder document,
        // so we return an error and let the client retry.
        if (parsed.message().isBlank()) {
            logger.warning("AI emitted format header but letter body is empty. Original response: " + aiDto.getMessage());
            return new OpenRouterResponseDto(false, null, "AI returned a format header but no letter body.", aiDto.getStatusCode(), OpenRouterErrorCode.UPSTREAM);
        }

        try {
            byte[] pdf = PdfGenerator.generatePdf(parsed.message());
            return new OpenRouterResponseDto(true, null, null, null, OpenRouterErrorCode.NONE, pdf);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "PDF generation failed", e);
            return new OpenRouterResponseDto(false, null, "PDF generation failed: " + e.getMessage(), null, OpenRouterErrorCode.INTERNAL);
        }
    }

    /**
     * Detects whether the user's message explicitly asks for the complaint to be anonymous.
     * The Ajuntament does not accept anonymous complaints, so we reject these early and clearly
     * rather than letting the AI draft an unusable letter.
     *
     * The check is intentionally conservative: we only match phrases where the user clearly states
     * they want anonymity, not every mention of the word "anonymous".
     */
    private boolean requestsAnonymity(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT)
                .replace("\u2018", "'").replace("\u2019", "'")
                .replaceAll("[.,;:!?()\\[\\]{}-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String[] anonymousPhrases = {
                "want to be anonymous",
                "remain anonymous",
                "stay anonymous",
                "keep it anonymous",
                "make it anonymous",
                "anonymously",
                "quiero ser anónimo",
                "quiero que sea anónimo",
                "de forma anónima",
                "de manera anónima",
                "en forma anónima",
                "quiero hacerlo anónimo",
                "vull ser anònim",
                "vull que sigui anònim",
                "de forma anònima",
                "de manera anònima",
                "vull fer-ho anònim"
        };
        for (String phrase : anonymousPhrases) {
            if (lower.contains(phrase)) {
                logger.fine("Anonymous intent detected by phrase: '" + phrase + "'");
                return true;
            }
        }
        return false;
    }


    /**
     * Detect whether the assistant explicitly refused because the request is not about El Prat.
     * This uses a small set of phrase checks that mirror the system prompt instruction.
     */
    private boolean aiRefusedAsNotAboutElPrat(String aiMessage) {
        if (aiMessage == null) return false;
        // Normalize common quotes/apostrophes for more reliable matching
        String normalized = aiMessage.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"');
        String lower = normalized.toLowerCase(Locale.ROOT).trim();
        // Remove punctuation for more robust matching (fix regex)
        String lowerNoPunct = lower.replaceAll("[.,;:!?()\\[\\]{}-]", " ").replaceAll("\\s+", " ").trim();

        // Common refusal patterns in English, Spanish and Catalan
        String[] refusalPhrases = new String[] {
                "can't help with",
                "cannot help with",
                "can't help",
                "cannot help",
                "can't assist",
                "cannot assist",
                "i'm sorry, i can't",
                "i'm sorry i can't",
                "i'm sorry, i cannot",
                "i'm sorry i cannot",
                "i cannot",
                "i can't",
                "i am unable to",
                "i'm unable to",
                "i cannot help",
                "i can't help",
                "cannot provide",
                "can't provide",
                "no puc ajudar",
                "no puc ajudar amb",
                "no puedo ayudar",
                "no puedo ayudar con",
                "lo siento, no puedo ayudar",
                "lo siento, no puedo",
                "no puedo",
                "no puc",
                "no puc ajudar",
                // Add more variants
                "not about el prat",
                "only answer about el prat",
                "only questions about el prat",
                "solo puedo responder sobre el prat",
                "només puc respondre sobre el prat",
                "solamente puedo responder sobre el prat",
                "solament puc respondre sobre el prat"
        };

        for (String p : refusalPhrases) {
            String pNoPunct = p.replaceAll("[.,;:!?()\\[\\]{}-]", " ").trim();
            if (lower.contains(p) || lowerNoPunct.contains(pNoPunct)) {
                logger.fine("Refusal detected by phrase: '" + p + "' in: " + lower);
                return true;
            }
        }

        // Also check for explicit mention that the model can only answer about El Prat
        if (lower.contains("el prat") && (lower.contains("only") || lower.contains("solament") || lower.contains("solo") || lower.contains("only about"))) {
            logger.fine("Refusal detected by El Prat + only/solament/solo/only about");
            return true;
        }
        return false;
    }

    private OpenRouterResponseDto callOpenRouterAndExtract(List<Map<String, Object>> messages) {
        logger.fine("callOpenRouterAndExtract: calling HttpWrapper");
        try {
            CompletableFuture<HttpDto> future = httpWrapper.postToOpenRouterAsync(messages);
            HttpDto dto = future.get(overallTimeoutSeconds, TimeUnit.SECONDS);
            logger.fine(() -> "callOpenRouterAndExtract: received dto=" + (dto == null ? "null" : String.valueOf(dto.statusCode())));
            if (dto == null) {
                logger.warning("callOpenRouterAndExtract: No response from AI service");
                return new OpenRouterResponseDto(false, null, "No response from AI service.", null, OpenRouterErrorCode.UPSTREAM);
            }
            if (dto.error() != null && !dto.error().isBlank()) {
                logger.log(Level.WARNING, "AI wrapper returned error: {0}", dto.error());
                return new OpenRouterResponseDto(false, dto.message(), dto.error(), dto.statusCode(), OpenRouterErrorCode.UPSTREAM);
            }
            if (dto.message() != null && !dto.message().isBlank()) {
                // If the AI refused because it's not about El Prat, map to a standardized error
                if (aiRefusedAsNotAboutElPrat(dto.message())) {
                    logger.info("AI refused - not about El Prat");
                    // Return success=false but PRESERVE the AI message so callers can log/display the assistant's text
                    return new OpenRouterResponseDto(false, dto.message(), "Request is not about El Prat de Llobregat.", dto.statusCode(), OpenRouterErrorCode.REFUSAL);
                }
                logger.fine("AI returned a message successfully");
                return new OpenRouterResponseDto(true, dto.message(), null, dto.statusCode(), OpenRouterErrorCode.NONE);
            }
            logger.warning("AI returned no message");
            return new OpenRouterResponseDto(false, null, "AI returned no message.", dto.statusCode(), OpenRouterErrorCode.UPSTREAM);
        } catch (TimeoutException te) {
            logger.log(Level.SEVERE, "AI service timed out", te);
            return new OpenRouterResponseDto(false, null, "AI service timed out.", null, OpenRouterErrorCode.TIMEOUT);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error calling AI service", e);
            return new OpenRouterResponseDto(false, null, e.getMessage(), null, OpenRouterErrorCode.INTERNAL);
        }
    }

    private void updateConversationHistory(String conversationId, String userMessage, String assistantMessage) {
        if (conversationId == null || conversationId.isBlank() || assistantMessage == null) return;
        List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
        if (history == null) history = new ArrayList<>();
        if (userMessage != null && !userMessage.isBlank()) {
            history.add(new MessageEntry("user", userMessage.trim()));
        }
        history.add(new MessageEntry("assistant", assistantMessage));
        // Cap history
        if (history.size() > MAX_HISTORY_TURNS * 2) {
            history = history.subList(history.size() - MAX_HISTORY_TURNS * 2, history.size());
        }
        conversationCache.put(conversationId, history);
    }
}

// Timeout is now configurable via the OPENROUTER_OVERALL_TIMEOUT_SECONDS environment variable (default 30s).
