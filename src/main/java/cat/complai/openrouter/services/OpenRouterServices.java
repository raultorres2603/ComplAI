package cat.complai.openrouter.services;

import cat.complai.openrouter.helpers.ProcedureRagHelper;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.Source;
import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.openrouter.helpers.ProcedureRagHelperRegistry;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micronaut.context.annotation.Value;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.*;

import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OutputFormat;
import cat.complai.openrouter.helpers.AiParsed;
import java.util.LinkedHashSet;

@Singleton
public class OpenRouterServices implements IOpenRouterService {

    private static final int MAX_HISTORY_TURNS = 10;
    private final HttpWrapper httpWrapper;
    private final RedactPromptBuilder promptBuilder;
    private final Logger logger = Logger.getLogger(OpenRouterServices.class.getName());
    private final Cache<String, List<MessageEntry>> conversationCache;
    // Stores the original complaint text for conversations where identity was missing on the first
    // turn. Keyed by conversationId. Cleared once the identity is complete and the letter is drafted.
    private final Cache<String, String> pendingComplaintCache;
    private final int maxInputLength;
    private final int overallTimeoutSeconds;
    private final ProcedureRagHelperRegistry ragRegistry;

    /**
     * Result type for procedure context extraction: context block string and the list of source URLs.
     */
    public static class ProcedureContextResult {
        private final String contextBlock;
        private final List<Source> sources;

        public ProcedureContextResult(String contextBlock, List<Source> sources) {
            this.contextBlock = contextBlock;
            this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
        }

        public String getContextBlock() { return contextBlock; }
        public List<Source> getSources() { return sources; }
    }

    public record MessageEntry(String role, String content) {}

    @Inject
    public OpenRouterServices(HttpWrapper httpWrapper,
                              @Value("${complai.input.max-length-chars:5000}") int maxInputLength,
                              @Value("${OPENROUTER_OVERALL_TIMEOUT_SECONDS:60}") int overallTimeoutSeconds,
                              RedactPromptBuilder promptBuilder,
                              ProcedureRagHelperRegistry ragRegistry) {
        this.httpWrapper    = Objects.requireNonNull(httpWrapper, "httpWrapper");
        this.promptBuilder  = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.ragRegistry   = Objects.requireNonNull(ragRegistry, "ragRegistry");
        this.conversationCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
        this.pendingComplaintCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
        this.maxInputLength = maxInputLength;
        this.overallTimeoutSeconds = (overallTimeoutSeconds > 0) ? overallTimeoutSeconds : 30;
    }

    // -------------------------------------------------------------------------
    // IOpenRouterService
    // -------------------------------------------------------------------------

    @Override
    public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
        if (complaint == null || complaint.isBlank()) {
            return Optional.of(new OpenRouterResponseDto(false, null, "Complaint must not be empty.", null, OpenRouterErrorCode.VALIDATION));
        }
        if (complaint.length() > maxInputLength) {
            return Optional.of(new OpenRouterResponseDto(false, null,
                    "Complaint exceeds maximum allowed length (" + maxInputLength + " characters).", null, OpenRouterErrorCode.VALIDATION));
        }
        if (requestsAnonymity(complaint)) {
            return Optional.of(new OpenRouterResponseDto(false, null,
                    "Anonymous complaints cannot be drafted. The Ajuntament requires full name and ID/DNI/NIF on all formal complaints.",
                    null, OpenRouterErrorCode.VALIDATION));
        }
        return Optional.empty();
    }


    @Override
    public OpenRouterResponseDto ask(String question, String conversationId, String cityId) {
        int inputLength = question != null ? question.length() : 0;
        logger.info(() -> "ask() called — conversationId=" + conversationId + " inputLength=" + inputLength + " city=" + cityId);
        if (question == null || question.isBlank()) {
            logger.fine(() -> "ask() rejected — reason=emptyQuestion conversationId=" + conversationId);
            return new OpenRouterResponseDto(false, null, "Question must not be empty.", null, OpenRouterErrorCode.VALIDATION);
        }
        if (question.length() > maxInputLength) {
            logger.fine(() -> "ask() rejected — reason=inputTooLong inputLength=" + inputLength
                    + " maxAllowed=" + maxInputLength + " conversationId=" + conversationId);
            return new OpenRouterResponseDto(false, null, "Question exceeds maximum allowed length (" + maxInputLength + " characters).", null, OpenRouterErrorCode.VALIDATION);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId)));
        
        // Only do expensive RAG search if question likely needs procedure information
        // This avoids unnecessary index lookups for conversational queries
        ProcedureContextResult procCtx = null;
        if (questionNeedsProcedureContext(question, cityId)) {
            procCtx = buildProcedureContextResult(question, cityId);
            if (procCtx.getContextBlock() != null) {
                messages.add(Map.of("role", "system", "content", procCtx.getContextBlock()));
            }
        }
        if (conversationId != null && !conversationId.isBlank()) {
            List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
            if (history != null) {
                for (MessageEntry entry : history) {
                    messages.add(Map.of("role", entry.role(), "content", entry.content()));
                }
            }
        }
        messages.add(Map.of("role", "user", "content", question.trim()));

        logger.fine(() -> "ask() messages prepared — messageCount=" + messages.size()
                + " conversationId=" + conversationId);
        OpenRouterResponseDto response = callOpenRouterAndExtract(messages, cityId);

        // Attach sources from procedure context only when there are actual sources (de-duped, ordered by relevance)
        if (response.isSuccess() && procCtx != null && !procCtx.getSources().isEmpty()) {
            response = new OpenRouterResponseDto(
                    response.isSuccess(),
                    response.getMessage(),
                    response.getError(),
                    response.getStatusCode(),
                    response.getErrorCode(),
                    response.getPdfData(),
                    deDuplicateAndOrderSources(procCtx.getSources())
            );
        }

        if (conversationId != null && !conversationId.isBlank() && response.getMessage() != null) {
            updateConversationHistory(conversationId, question, response.getMessage());
        }
        return response;
    }

    @Override
    public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity, String cityId) {
        int inputLength = complaint != null ? complaint.length() : 0;
        boolean identityProvided = identity != null && identity.isPartiallyProvided();
        logger.info(() -> "redactComplaint() called — conversationId=" + conversationId
                + " inputLength=" + inputLength + " format=" + format + " identityProvided=" + identityProvided + " city=" + cityId);

        Optional<OpenRouterResponseDto> validationError = validateRedactInput(complaint);
        if (validationError.isPresent()) {
            logger.fine(() -> "redactComplaint() rejected — reason=" + validationError.get().getError()
                    + " conversationId=" + conversationId);
            return validationError.get();
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId)));
        
        // Only add procedure context if we have complete identity (ready to draft)
        // Skip RAG search during identity collection to improve latency
        String contextBlock;
        boolean hasCompleteIdentity = identity != null && identity.isComplete();
        if (hasCompleteIdentity) {
            contextBlock = promptBuilder.buildProcedureContextBlock(complaint, cityId);
            if (contextBlock != null) {
                messages.add(Map.of("role", "system", "content", contextBlock));
            }
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
        String userPrompt = "";
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
                logger.fine(() -> "redactComplaint() resumed stored complaint — conversationId=" + conversationId
                        + " originalLength=" + originalComplaint.length());
            }
            String complaintForPrompt = (originalComplaint != null)
                    ? originalComplaint + "\n\n" + complaint
                    : complaint;
            if (complaintForPrompt != null) {
                userPrompt = promptBuilder.buildRedactPromptWithIdentity(complaintForPrompt, identity, cityId);
            }
        } else {
            if (conversationId != null && !conversationId.isBlank()) {
                if (complaint != null) {
                    pendingComplaintCache.put(conversationId, complaint);
                }
                logger.fine(() -> "redactComplaint() saved complaint for identity follow-up — conversationId=" + conversationId);
            }
            userPrompt = promptBuilder.buildRedactPromptRequestingIdentity(complaint, identity, cityId);
        }

        messages.add(Map.of("role", "user", "content", userPrompt));

        logger.fine(() -> "redactComplaint() messages prepared — messageCount=" + messages.size()
                + " identityComplete=" + identityComplete + " conversationId=" + conversationId);
        OpenRouterResponseDto aiDto = callOpenRouterAndExtract(messages, cityId);

        // Propagate any non-success result immediately.
        if (aiDto.getErrorCode() != OpenRouterErrorCode.NONE) {
            return aiDto;
        }

        if (conversationId != null && !conversationId.isBlank() && aiDto.getMessage() != null) {
            updateConversationHistory(conversationId, userPrompt, aiDto.getMessage());
        }

        AiParsed parsed = AiParsed.parseAiFormatHeader(aiDto.getMessage());

        // Identity incomplete: the AI is asking the user for missing fields.
        // Return its question as text so the client can display it.
        if (!identityComplete) {
            return new OpenRouterResponseDto(true, parsed.message(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
        }

        // Graceful fallback when the AI omits the required JSON header: return the raw message.
        // PDF generation has been removed from the sync path — PDFs are always produced by the
        // async worker Lambda.
        if (parsed.format() == null || parsed.format() == OutputFormat.AUTO) {
            String rawPreview = aiDto.getMessage() == null ? "<null>"
                    : (aiDto.getMessage().length() > 200 ? aiDto.getMessage().substring(0, 200) + "..." : aiDto.getMessage());
            logger.warning("AI response missing required JSON header; raw response prefix: " + rawPreview);
            return new OpenRouterResponseDto(true, aiDto.getMessage(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
        }

        // Header present: return the extracted letter body as text.
        return new OpenRouterResponseDto(true, parsed.message(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
    }



    /**
     * Detects whether the user's message explicitly asks for the complaint to be anonymous.
     * The Ajuntament does not accept anonymous complaints, so we reject these early and clearly
     * rather than letting the AI draft an unusable letter.
     * The check is intentionally conservative: we only match phrases where the user clearly states
     * they want anonymity, not every mention of the word "anonymous".
     */
    private boolean requestsAnonymity(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT)
                .replace("‘", "'").replace("’", "'")
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
     * Detect whether the assistant explicitly refused because the request is not about the
     * given city. Generic refusal phrases are city-agnostic and detected first; a secondary
     * check looks for the city name paired with scope-limiting words.
     */
    private boolean aiRefusedAsOffTopic(String aiMessage, String cityId) {
        if (aiMessage == null) return false;
        // Normalize typographic quotes/apostrophes for reliable matching.
        String normalized = aiMessage
                .replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"');
        String lower = normalized.toLowerCase(Locale.ROOT).trim();
        String lowerNoPunct = lower.replaceAll("[.,;:!?()\\[\\]{}-]", " ").replaceAll("\\s+", " ").trim();

        // Generic refusal patterns (city-agnostic).
        String[] refusalPhrases = {
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
        };

        for (String p : refusalPhrases) {
            String pNoPunct = p.replaceAll("[.,;:!?()\\[\\]{}-]", " ").trim();
            if (lower.contains(p) || lowerNoPunct.contains(pNoPunct)) {
                logger.fine("Refusal detected by phrase: '" + p + "' in: " + lower);
                return true;
            }
        }

        // City-specific secondary check: the AI mentioned the city name alongside scope-limiting language.
        String cityNameLower = RedactPromptBuilder.resolveCityDisplayName(cityId).toLowerCase(Locale.ROOT);
        if (lower.contains(cityNameLower)
                && (lower.contains("only") || lower.contains("solament") || lower.contains("solo") || lower.contains("only about"))) {
            logger.fine("Refusal detected: city name '" + cityNameLower + "' + scope-limiting word");
            return true;
        }

        return false;
    }

    private OpenRouterResponseDto callOpenRouterAndExtract(List<Map<String, Object>> messages, String cityId) {
        logger.fine(() -> "callOpenRouterAndExtract — sending " + messages.size() + " messages to OpenRouter");
        try {
            CompletableFuture<HttpDto> future = httpWrapper.postToOpenRouterAsync(messages);
            HttpDto dto = future.get(overallTimeoutSeconds, TimeUnit.SECONDS);
            if (dto == null) {
                logger.warning("callOpenRouterAndExtract — OpenRouter returned null response (no HTTP status)");
                return new OpenRouterResponseDto(false, null, "No response from AI service.", null, OpenRouterErrorCode.UPSTREAM);
            }
            logger.fine(() -> "callOpenRouterAndExtract — OpenRouter responded httpStatus=" + dto.statusCode()
                    + " hasMessage=" + (dto.message() != null && !dto.message().isBlank())
                    + " hasError=" + (dto.error() != null && !dto.error().isBlank()));
            if (dto.error() != null && !dto.error().isBlank()) {
                logger.warning(() -> "callOpenRouterAndExtract — OpenRouter error httpStatus=" + dto.statusCode()
                        + " error=" + dto.error());
                return new OpenRouterResponseDto(false, dto.message(), dto.error(), dto.statusCode(), OpenRouterErrorCode.UPSTREAM);
            }
            if (dto.message() != null && !dto.message().isBlank()) {
                if (aiRefusedAsOffTopic(dto.message(), cityId)) {
                    String cityName = RedactPromptBuilder.resolveCityDisplayName(cityId);
                    logger.info(() -> "callOpenRouterAndExtract — AI refused (off-topic for city=" + cityId
                            + ") httpStatus=" + dto.statusCode());
                    return new OpenRouterResponseDto(false, dto.message(),
                            "Request is not about " + cityName + ".",
                            dto.statusCode(), OpenRouterErrorCode.REFUSAL);
                }
                logger.fine(() -> "callOpenRouterAndExtract — AI responded successfully httpStatus=" + dto.statusCode()
                        + " responseLength=" + dto.message().length());
                return new OpenRouterResponseDto(true, dto.message(), null, dto.statusCode(), OpenRouterErrorCode.NONE);
            }
            logger.warning(() -> "callOpenRouterAndExtract — AI returned empty message httpStatus=" + dto.statusCode());
            return new OpenRouterResponseDto(false, null, "AI returned no message.", dto.statusCode(), OpenRouterErrorCode.UPSTREAM);
        } catch (TimeoutException te) {
            logger.log(Level.SEVERE, "callOpenRouterAndExtract — AI service timed out after " + overallTimeoutSeconds + "s", te);
            return new OpenRouterResponseDto(false, null, "AI service timed out.", null, OpenRouterErrorCode.TIMEOUT);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "callOpenRouterAndExtract — unexpected exception calling AI service", e);
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

    /**
     * Determines if a question likely needs procedure/municipal information.
     * This avoids expensive RAG searches for conversational queries.
     * Checks against actual procedure titles for more accurate detection.
     */
    private boolean questionNeedsProcedureContext(String question, String cityId) {
        if (question == null || question.isBlank()) return false;
        
        String lower = question.toLowerCase();
        
        // First, check against actual procedure titles for this city (most accurate)
        try {
            ProcedureRagHelper helper = ragRegistry.getForCity(cityId);
            List<ProcedureRagHelper.Procedure> procedures = helper.getAllProcedures();
            for (ProcedureRagHelper.Procedure proc : procedures) {
                if (lower.contains(proc.title.toLowerCase())) {
                    return true;
                }
            }
        } catch (Exception e) {
            // Fallback to keyword detection if procedure loading fails
            logger.fine(() -> "Failed to load procedures for title checking: " + e.getMessage());
        }
        
        // Keywords that indicate user wants procedural/municipal information
        String[] proceduralKeywords = {
            "how to", "how do i", "what is the process", "procedure", "tramit", "tràmit",
            "requirement", "requirements", "document", "documents", "apply", "application",
            "form", "forms", "permit", "license", "request", "complaint", "claim",
            "where can i", "how can i", "steps", "step by step", "process",
            "recycling", "waste", "garbage", "trash", "center", "collection"
        };
        
        for (String keyword : proceduralKeywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        
        // Questions about specific municipal services
        return lower.contains("ajuntament") || lower.contains("city hall") ||
                lower.contains("municipal") || lower.contains("council");
    }

    private ProcedureContextResult buildProcedureContextResult(String query, String cityId) {
        try {
            ProcedureRagHelper helper = ragRegistry.getForCity(cityId);
            List<ProcedureRagHelper.Procedure> matches = helper.search(query);
            if (matches.isEmpty()) {
                return new ProcedureContextResult(null, List.of());
            }
            List<Source> sources = matches.stream()
                    .map(p -> new Source(p.url, p.title))
                    .filter(source -> source.getUrl() != null && !source.getUrl().isBlank())
                    .toList();
            String contextBlock = promptBuilder.buildProcedureContextBlockFromMatches(matches, cityId);
            return new ProcedureContextResult(contextBlock, sources);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to build procedure context result for city=" + cityId
                    + "; returning empty context: " + e.getMessage(), e);
            return new ProcedureContextResult(null, List.of());
        }
    }

    /**
     * De-duplicates sources by URL and preserves order: first occurrence wins, stable ordering.
     */
    private List<Source> deDuplicateAndOrderSources(List<Source> sources) {
        LinkedHashSet<String> seenUrls = new LinkedHashSet<>();
        List<Source> deduped = new ArrayList<>();
        for (Source source : sources) {
            if (seenUrls.add(source.getUrl())) {
                deduped.add(source);
            }
        }
        return List.copyOf(deduped);
    }
}

// Timeout is now configurable via the OPENROUTER_OVERALL_TIMEOUT_SECONDS environment variable (default 30s).
