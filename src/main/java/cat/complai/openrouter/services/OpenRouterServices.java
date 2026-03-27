package cat.complai.openrouter.services;

import cat.complai.http.HttpWrapper;
import cat.complai.openrouter.services.validation.InputValidationService;
import cat.complai.openrouter.services.conversation.ConversationManagementService;
import cat.complai.openrouter.services.ai.AiResponseProcessingService;
import cat.complai.openrouter.services.procedure.ProcedureContextService;
import cat.complai.openrouter.services.procedure.ProcedureContextService.ProcedureContextResult;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.Source;
import cat.complai.openrouter.helpers.LanguageDetector;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.openrouter.helpers.SseChunkParser;
import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OutputFormat;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import org.reactivestreams.Publisher;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class OpenRouterServices implements IOpenRouterService {

    private final Logger logger = Logger.getLogger(OpenRouterServices.class.getName());
    private final InputValidationService validationService;
    private final ConversationManagementService conversationService;
    private final AiResponseProcessingService aiResponseService;
    private final ProcedureContextService procedureContextService;
    private final RedactPromptBuilder promptBuilder;
    private final HttpWrapper httpWrapper;

    /**
     * Estimates token count for text using OpenAI's rule of thumb: ~1 token per 4
     * characters.
     */
    private static int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    /**
     * Compute a deterministic hash of RAG sources for cache key generation.
     * 
     * PRIVACY NOTE: Hashing the sources is safe for caching because:
     * - Sources contain only public document titles and URLs
     * - No user query text or conversation history included
     * - Hash is deterministic: same sources always produce same hash
     * - Different procedure/event sets produce different hashes
     * 
     * @param sources The list of Source objects from RAG
     * @return A hash of the sources (0 if empty)
     */
    private static long computeSourcesHash(List<Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }

        // Create a deterministic hash from source titles and URLs
        StringBuilder sb = new StringBuilder();
        for (Source source : sources) {
            String title = source.getTitle();
            String url = source.getUrl();
            if (title != null)
                sb.append(title).append("|");
            if (url != null)
                sb.append(url).append("|");
        }

        // Use Java's default hashCode (not cryptographically secure, but good for
        // caching)
        return sb.toString().hashCode();
    }

    @Inject
    public OpenRouterServices(InputValidationService validationService,
            ConversationManagementService conversationService,
            AiResponseProcessingService aiResponseService,
            ProcedureContextService procedureContextService,
            RedactPromptBuilder promptBuilder,
            HttpWrapper httpWrapper) {
        this.validationService = validationService;
        this.conversationService = conversationService;
        this.aiResponseService = aiResponseService;
        this.procedureContextService = procedureContextService;
        this.promptBuilder = promptBuilder;
        this.httpWrapper = httpWrapper;
    }

    // -------------------------------------------------------------------------
    // IOpenRouterService
    // -------------------------------------------------------------------------

    @Override
    public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
        return validationService.validateRedactInput(complaint);
    }

    @Override
    public OpenRouterResponseDto ask(String question, String conversationId, String cityId) {
        int inputLength = question != null ? question.length() : 0;
        logger.info(() -> "ask() called — conversationId=" + conversationId + " inputLength=" + inputLength + " city="
                + cityId);

        var validationError = validationService.validateQuestion(question);
        if (validationError.isPresent()) {
            logger.fine(() -> "ask() rejected — reason=" + validationError.get().getError() + " conversationId="
                    + conversationId);
            return validationError.get();
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        String detectedLanguage = cat.complai.openrouter.helpers.LanguageDetector.detect(question);
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId, detectedLanguage)));

        // Parallelize RAG searches for procedure and event context
        // Start both searches concurrently to reduce latency
        long ragStartTime = System.currentTimeMillis();
        CompletableFuture<ProcedureContextResult> procFuture = procedureContextService
                .questionNeedsProcedureContext(question, cityId)
                        ? procedureContextService.buildProcedureContextResultAsync(question, cityId)
                        : CompletableFuture.completedFuture(null);

        CompletableFuture<ProcedureContextService.EventContextResult> eventFuture = procedureContextService
                .questionNeedsEventContext(question, cityId)
                        ? procedureContextService.buildEventContextResultAsync(question, cityId)
                        : CompletableFuture.completedFuture(null);

        // Wait for both searches to complete
        ProcedureContextResult procCtx = null;
        ProcedureContextService.EventContextResult eventCtx = null;
        try {
            CompletableFuture.allOf(procFuture, eventFuture).join();
            procCtx = procFuture.get();
            eventCtx = eventFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.WARNING,
                    "Async RAG search failed — error=" + e.getMessage() + " conversationId=" + conversationId, e);
            procCtx = null;
            eventCtx = null;
        }
        long ragEndTime = System.currentTimeMillis();
        logger.fine(() -> "ask() RAG search completed — time=" + (ragEndTime - ragStartTime) + "ms conversationId="
                + conversationId);

        // Add procedure context if available
        if (procCtx != null && procCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", procCtx.getContextBlock()));
        }

        // Add event context if available
        if (eventCtx != null && eventCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", eventCtx.getContextBlock()));
        }

        // Add conversation history
        var history = conversationService.getConversationHistory(conversationId);
        conversationService.addToMessages(messages, history);

        if (question != null) {
            messages.add(Map.of("role", "user", "content", question.trim()));
        } else {
            messages.add(Map.of("role", "user", "content", ""));
        }

        // Calculate and log context metrics
        final int systemTokens;
        final int historyTokens;
        final int userTokens;
        int tmpSystemTokens = 0;
        int tmpHistoryTokens = 0;
        int tmpUserTokens = 0;
        for (Map<String, Object> msg : messages) {
            String content = (String) msg.get("content");
            if (content == null)
                continue;
            String role = (String) msg.get("role");
            int tokens = estimateTokenCount(content);

            if ("system".equals(role)) {
                tmpSystemTokens += tokens;
            } else if (!"user".equals(role)) {
                tmpHistoryTokens += tokens;
            }
        }
        tmpUserTokens = estimateTokenCount(question);
        systemTokens = tmpSystemTokens;
        historyTokens = tmpHistoryTokens;
        userTokens = tmpUserTokens;
        final int totalTokens = systemTokens + historyTokens + userTokens;
        final int historyTurns = history != null ? (history.size() / 2) : 0;

        logger.fine(() -> "ask() CONTEXT METRICS — systemTokens=" + systemTokens
                + " historyTokens=" + historyTokens
                + " userTokens=" + userTokens
                + " totalTokens=" + totalTokens
                + " historyTurns=" + historyTurns
                + " conversationId=" + conversationId);

        logger.fine(() -> "ask() messages prepared — messageCount=" + messages.size()
                + " conversationId=" + conversationId);

        // Calculate context hashes for caching (deterministic, reproducible)
        long procContextHash = computeSourcesHash(procCtx != null ? procCtx.getSources() : List.of());
        long eventContextHash = computeSourcesHash(eventCtx != null ? eventCtx.getSources() : List.of());

        OpenRouterResponseDto response = aiResponseService.callOpenRouterAndExtract(messages, cityId, procContextHash,
                eventContextHash);

        // Collect all sources WITHOUT deduplication - symmetric handling
        // Procedure and event sources are treated equally at collection time
        List<Source> mergedSources = new ArrayList<>();
        if (procCtx != null && !procCtx.getSources().isEmpty()) {
            mergedSources.addAll(procCtx.getSources());
        }
        if (eventCtx != null && !eventCtx.getSources().isEmpty()) {
            mergedSources.addAll(eventCtx.getSources());
        }

        // Deduplicate ONCE after all sources are collected
        // This ensures symmetric handling and prevents double deduplication
        if (response.isSuccess() && !mergedSources.isEmpty()) {
            List<Source> finalSources = procedureContextService.deDuplicateAndOrderSources(mergedSources);
            response = new OpenRouterResponseDto(
                    response.isSuccess(),
                    response.getMessage(),
                    response.getError(),
                    response.getStatusCode(),
                    response.getErrorCode(),
                    response.getPdfData(),
                    finalSources);
        }

        if (conversationId != null && !conversationId.isBlank() && response.getMessage() != null) {
            conversationService.updateConversationHistory(conversationId, question, response.getMessage());
        }
        return response;
    }

    @Override
    public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId,
            ComplainantIdentity identity, String cityId) {
        int inputLength = complaint != null ? complaint.length() : 0;
        boolean identityProvided = identity != null && identity.isPartiallyProvided();
        logger.info(() -> "redactComplaint() called — conversationId=" + conversationId
                + " inputLength=" + inputLength + " format=" + format + " identityProvided=" + identityProvided
                + " city=" + cityId);

        var validationError = validationService.validateRedactInput(complaint);
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
        // Add conversation history
        var history = conversationService.getConversationHistory(conversationId);
        conversationService.addToMessages(messages, history);

        // Choose the prompt based on whether we have a complete identity.
        // When identity is incomplete, the AI is instructed to ask for the missing
        // fields first.
        // The caller is expected to collect the AI's question, present it to the user,
        // and
        // re-submit with the full identity in a subsequent request.
        String userPrompt = "";
        final boolean identityComplete = identity != null && identity.isComplete();
        if (identityComplete) {
            // On the second turn the user's message contains the identity data they typed
            // (e.g.
            // "My name is Raul Torres, DNI 49872354C, I live at Carrer Major 10"). The
            // original
            // complaint was saved on the first turn. We combine both so that any optional
            // contact
            // details the user included in their identity reply (address, phone, etc.) are
            // visible
            // to the prompt and can be included in the letter.
            String originalComplaint = conversationService.getPendingComplaint(conversationId);
            if (originalComplaint != null) {
                conversationService.clearPendingComplaint(conversationId);
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
                conversationService.storePendingComplaint(conversationId, complaint);
                logger.fine(() -> "redactComplaint() saved complaint for identity follow-up — conversationId="
                        + conversationId);
            }
            userPrompt = promptBuilder.buildRedactPromptRequestingIdentity(complaint, identity, cityId);
        }

        messages.add(Map.of("role", "user", "content", userPrompt));

        logger.fine(() -> "redactComplaint() messages prepared — messageCount=" + messages.size()
                + " identityComplete=" + identityComplete + " conversationId=" + conversationId);

        // No procedure/event context for complaints, use default cache key
        OpenRouterResponseDto aiDto = aiResponseService.callOpenRouterAndExtract(messages, cityId, 0, 0);

        // Process the AI response using the dedicated service
        OpenRouterResponseDto processedResponse = aiResponseService.processComplaintResponse(aiDto, identityComplete);

        // Update conversation history if we have a valid response
        if (conversationId != null && !conversationId.isBlank() && processedResponse.getMessage() != null) {
            conversationService.updateConversationHistory(conversationId, userPrompt, processedResponse.getMessage());
        }

        return processedResponse;
    }

    @Override
    public Publisher<String> streamAsk(String question, String conversationId, String cityId) {
        // 1. Validate input
        var validationError = validationService.validateQuestion(question);
        if (validationError.isPresent()) {
            String errorText = validationError.get().getError();
            return reactor.core.publisher.Flux.just(errorText != null ? errorText : "Invalid input.");
        }

        // 2. Single-language system message + RAG (same pattern as ask())
        String detectedLanguage = LanguageDetector.detect(question);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId, detectedLanguage)));

        // 3. Parallelize RAG — blocking join is acceptable (Lucene in-memory, fast)
        CompletableFuture<ProcedureContextResult> procFuture =
                procedureContextService.questionNeedsProcedureContext(question, cityId)
                        ? procedureContextService.buildProcedureContextResultAsync(question, cityId)
                        : CompletableFuture.completedFuture(null);
        CompletableFuture<ProcedureContextService.EventContextResult> eventFuture =
                procedureContextService.questionNeedsEventContext(question, cityId)
                        ? procedureContextService.buildEventContextResultAsync(question, cityId)
                        : CompletableFuture.completedFuture(null);

        ProcedureContextResult procCtx = null;
        ProcedureContextService.EventContextResult eventCtx = null;
        try {
            CompletableFuture.allOf(procFuture, eventFuture).join();
            procCtx = procFuture.get();
            eventCtx = eventFuture.get();
        } catch (Exception e) {
            logger.log(Level.WARNING, "streamAsk() RAG failed — conversationId=" + conversationId, e);
        }

        if (procCtx != null && procCtx.getContextBlock() != null)
            messages.add(Map.of("role", "system", "content", procCtx.getContextBlock()));
        if (eventCtx != null && eventCtx.getContextBlock() != null)
            messages.add(Map.of("role", "system", "content", eventCtx.getContextBlock()));

        var history = conversationService.getConversationHistory(conversationId);
        conversationService.addToMessages(messages, history);
        messages.add(Map.of("role", "user", "content", question != null ? question.trim() : ""));

        // 4. Stream — accumulate full text as side effect for conversation history
        final String capturedQuestion = question;
        final String capturedCityId = cityId;
        final String capturedConvId = conversationId;
        final StringBuilder assembled = new StringBuilder();

        return reactor.core.publisher.Flux.from(httpWrapper.streamFromOpenRouter(messages))
                .map(SseChunkParser::parseDelta)
                .filter(delta -> delta != null && !delta.isEmpty())
                .doOnNext(assembled::append)
                .doOnComplete(() -> {
                    String fullText = assembled.toString();
                    if (capturedConvId != null && !capturedConvId.isBlank() && !fullText.isBlank()) {
                        conversationService.updateConversationHistory(capturedConvId, capturedQuestion, fullText);
                    }
                    logger.info(() -> "streamAsk() completed — conversationId=" + capturedConvId
                            + " assembledLength=" + fullText.length() + " city=" + capturedCityId);
                })
                .doOnError(e -> logger.log(Level.SEVERE,
                        "streamAsk() OpenRouter stream error — conversationId=" + capturedConvId, e));
    }

}
