package cat.complai.openrouter.services;

import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.OpenRouterStreamStartResult;
import cat.complai.openrouter.services.validation.InputValidationService;
import cat.complai.openrouter.services.conversation.ConversationManagementService;
import cat.complai.openrouter.services.ai.AiResponseProcessingService;
import cat.complai.openrouter.services.procedure.ProcedureContextService;
import cat.complai.openrouter.services.procedure.ProcedureContextService.ContextRequirements;
import cat.complai.openrouter.services.procedure.ProcedureContextService.ProcedureContextResult;
import cat.complai.openrouter.services.procedure.ProcedureContextService.NewsContextResult;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.dto.AskStreamResult;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.Source;
import cat.complai.openrouter.dto.sse.SseChunkEvent;
import cat.complai.openrouter.dto.sse.SseSources;
import cat.complai.openrouter.dto.sse.SseSourcesEvent;
import cat.complai.openrouter.dto.sse.SseDoneEvent;
import cat.complai.openrouter.dto.sse.SseErrorEvent;
import cat.complai.openrouter.helpers.AskStreamErrorMapper;
import cat.complai.openrouter.helpers.LanguageDetector;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.openrouter.helpers.SseChunkParser;
import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OutputFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Singleton
public class OpenRouterServices implements IOpenRouterService {

    private final Logger logger = Logger.getLogger(OpenRouterServices.class.getName());
    private final InputValidationService validationService;
    private final ConversationManagementService conversationService;
    private final AiResponseProcessingService aiResponseService;
    private final ProcedureContextService procedureContextService;
    private final RedactPromptBuilder promptBuilder;
    private final HttpWrapper httpWrapper;
    private final ObjectMapper objectMapper;
    private final ExecutorService ragExecutor;

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
            HttpWrapper httpWrapper,
            ObjectMapper objectMapper) {
        this.validationService = validationService;
        this.conversationService = conversationService;
        this.aiResponseService = aiResponseService;
        this.procedureContextService = procedureContextService;
        this.promptBuilder = promptBuilder;
        this.httpWrapper = httpWrapper;
        this.objectMapper = objectMapper;
        this.ragExecutor = createRagExecutor();
    }

    @PreDestroy
    void shutdownRagExecutor() {
        ragExecutor.shutdown();
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

        if (procedureContextService.requiresEventDateWindowClarification(question, cityId)) {
            String clarificationMessage = buildEventDateWindowClarificationMessage(detectedLanguage);
            if (conversationId != null && !conversationId.isBlank()) {
            conversationService.updateConversationHistory(conversationId, question, clarificationMessage);
            }
            logger.info(() -> "ask() event date-window clarification triggered - conversationId=" + conversationId
                + " city=" + cityId);
            return new OpenRouterResponseDto(true, clarificationMessage, null, 200, OpenRouterErrorCode.NONE, null,
                List.of());
        }

        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId, detectedLanguage)));

        RagContexts ragContexts = buildRagContexts(question, cityId, conversationId, "ask()");
        ProcedureContextResult procCtx = ragContexts.procedureContext();
        ProcedureContextService.EventContextResult eventCtx = ragContexts.eventContext();
        NewsContextResult newsCtx = ragContexts.newsContext();

        if (ragContexts.newsIntent() && (newsCtx == null || newsCtx.getSources().isEmpty())) {
            String fallbackMessage = buildNoNewsFoundMessage(detectedLanguage, cityId);
            if (conversationId != null && !conversationId.isBlank()) {
                conversationService.updateConversationHistory(conversationId, question, fallbackMessage);
            }
            logger.info(() -> "ask() news fallback used - conversationId=" + conversationId + " city=" + cityId);
            return new OpenRouterResponseDto(true, fallbackMessage, null, 200, OpenRouterErrorCode.NONE, null,
                    List.of());
        }

        // Add procedure context if available
        if (procCtx != null && procCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", procCtx.getContextBlock()));
        }

        // Add event context if available
        if (eventCtx != null && eventCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", eventCtx.getContextBlock()));
        }

        if (newsCtx != null && newsCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", newsCtx.getContextBlock()));
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
        List<Source> eventAndNewsSourcesForHash = new ArrayList<>();
        if (eventCtx != null && eventCtx.getSources() != null) {
            eventAndNewsSourcesForHash.addAll(eventCtx.getSources());
        }
        if (newsCtx != null && newsCtx.getSources() != null) {
            eventAndNewsSourcesForHash.addAll(newsCtx.getSources());
        }
        long eventContextHash = computeSourcesHash(eventAndNewsSourcesForHash);

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
        if (newsCtx != null && !newsCtx.getSources().isEmpty()) {
            mergedSources.addAll(newsCtx.getSources());
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
    public AskStreamResult streamAsk(String question, String conversationId, String cityId) {
        // 1. Validate input — emit error event if invalid
        var validationError = validationService.validateQuestion(question);
        if (validationError.isPresent()) {
            String errorText = validationError.get().getError();
            errorText = errorText != null ? errorText : "Invalid input.";
            try {
                SseErrorEvent errorEvent = new SseErrorEvent(errorText, OpenRouterErrorCode.VALIDATION.getCode());
                String json = objectMapper.writeValueAsString(errorEvent);
                return new AskStreamResult.Success(Flux.just(json));
            } catch (Exception e) {
                logger.log(Level.SEVERE, "streamAsk() failed to serialize validation error", e);
                return new AskStreamResult.Error(AskStreamErrorMapper.toResponseDto(e));
            }
        }

        // 2. Single-language system message + RAG
        String detectedLanguage = LanguageDetector.detect(question);

        if (procedureContextService.requiresEventDateWindowClarification(question, cityId)) {
            String clarificationMessage = buildEventDateWindowClarificationMessage(detectedLanguage);
            logger.info(() -> "streamAsk() event date-window clarification triggered - conversationId="
                + conversationId + " city=" + cityId);
            return buildFallbackStreamResult(clarificationMessage, conversationId, question);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId, detectedLanguage)));

        RagContexts ragContexts = buildRagContexts(question, cityId, conversationId, "streamAsk()");
        ProcedureContextResult procCtx = ragContexts.procedureContext();
        ProcedureContextService.EventContextResult eventCtx = ragContexts.eventContext();
        NewsContextResult newsCtx = ragContexts.newsContext();

        if (ragContexts.newsIntent() && (newsCtx == null || newsCtx.getSources().isEmpty())) {
            String fallbackMessage = buildNoNewsFoundMessage(detectedLanguage, cityId);
            logger.info(() -> "streamAsk() news fallback used - conversationId=" + conversationId + " city=" + cityId);
            return buildFallbackStreamResult(fallbackMessage, conversationId, question);
        }

        if (procCtx != null && procCtx.getContextBlock() != null)
            messages.add(Map.of("role", "system", "content", procCtx.getContextBlock()));
        if (eventCtx != null && eventCtx.getContextBlock() != null)
            messages.add(Map.of("role", "system", "content", eventCtx.getContextBlock()));
        if (newsCtx != null && newsCtx.getContextBlock() != null)
            messages.add(Map.of("role", "system", "content", newsCtx.getContextBlock()));

        var history = conversationService.getConversationHistory(conversationId);
        conversationService.addToMessages(messages, history);
        messages.add(Map.of("role", "user", "content", question != null ? question.trim() : ""));

        // 4. Collect sources from RAG context
        List<SseSources> allSources = new ArrayList<>();
        if (procCtx != null && procCtx.getSources() != null) {
            allSources.addAll(procCtx.getSources().stream()
                    .map(s -> new SseSources(s.getTitle(), s.getUrl()))
                    .collect(Collectors.toList()));
        }
        if (eventCtx != null && eventCtx.getSources() != null) {
            allSources.addAll(eventCtx.getSources().stream()
                    .map(s -> new SseSources(s.getTitle(), s.getUrl()))
                    .collect(Collectors.toList()));
        }
        if (newsCtx != null && newsCtx.getSources() != null) {
            allSources.addAll(newsCtx.getSources().stream()
                    .map(s -> new SseSources(s.getTitle(), s.getUrl()))
                    .collect(Collectors.toList()));
        }

        // 5. Stream chunks, then emit sources, then done
        final String capturedQuestion = question;
        final String capturedCityId = cityId;
        final String capturedConvId = conversationId;
        final StringBuilder assembled = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean hasEmitted = new java.util.concurrent.atomic.AtomicBoolean(
                false);

        logger.fine(() -> "streamAsk() preparing to stream — conversationId=" + capturedConvId + " sources="
                + allSources.size());

        // Verify objectMapper is not null before proceeding
        if (objectMapper == null) {
            logger.severe("streamAsk() ERROR: objectMapper is null! This should never happen.");
            return new AskStreamResult.Error(AskStreamErrorMapper.toResponseDto(
                    new IllegalStateException("ObjectMapper is not properly injected")));
        }

        OpenRouterStreamStartResult streamStartResult = httpWrapper.streamFromOpenRouter(messages);
        if (streamStartResult instanceof OpenRouterStreamStartResult.Error error) {
            return new AskStreamResult.Error(AskStreamErrorMapper.toResponseDto(error.failure()));
        }

        Publisher<String> stream = ((OpenRouterStreamStartResult.Success) streamStartResult).stream();

        return new AskStreamResult.Success(Flux.from(stream)
                .doOnSubscribe(sub -> {
                }) // Signal subscription start (debugging removed)
                .handle((raw, sink) -> {
                    SseChunkParser.ParseResult parsed = SseChunkParser.parseLine(raw);
                    if (parsed.state() == SseChunkParser.ParseState.MALFORMED) {
                        sink.error(new IllegalStateException("Malformed upstream stream chunk"));
                        return;
                    }
                    if (parsed.state() == SseChunkParser.ParseState.DELTA && parsed.delta() != null
                            && !parsed.delta().isEmpty()) {
                        sink.next(parsed.delta());
                    }
                })
                .cast(String.class)
                .doOnNext(delta -> {
                    assembled.append(delta);
                    hasEmitted.set(true);
                    logger.fine(() -> "streamAsk() chunk delta processed");
                })
                .map(delta -> {
                    // Serialize each chunk delta to JSON
                    try {
                        SseChunkEvent chunk = new SseChunkEvent(delta);
                        String serialized = objectMapper.writeValueAsString(chunk);
                        logger.fine(() -> "streamAsk() emitting chunk event serialized");
                        return serialized;
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "Failed to serialize chunk", e);
                        throw new RuntimeException("Chunk serialization failed", e);
                    }
                })
                .doOnComplete(() -> {
                    logger.info("First Flux completed");
                })
                .concatWith(produceSourcesAndDone(allSources, capturedConvId))
                .doOnNext(event -> logger.fine(() -> "streamAsk() emitting event"))
                .doOnComplete(() -> {
                    String fullText = assembled.toString();
                    if (capturedConvId != null && !capturedConvId.isBlank() && !fullText.isBlank()) {
                        conversationService.updateConversationHistory(capturedConvId, capturedQuestion, fullText);
                    }
                    logger.info(() -> "streamAsk() completed — conversationId=" + capturedConvId
                            + " assembledLength=" + fullText.length() + " city=" + capturedCityId);
                })
                .doOnError(e -> {
                    logger.log(Level.SEVERE, "streamAsk() error during streaming — conversationId=" + capturedConvId,
                            e);
                })
                .onErrorResume(e -> {
                    // Only convert to SSE error event if data has already been emitted.
                    // If nothing was emitted yet, let the error propagate so Micronaut
                    // can return an HTTP 5xx response (no headers committed yet).
                    if (!hasEmitted.get()) {
                        return Flux.error(e);
                    }
                    try {
                        SseErrorEvent errorEvent = AskStreamErrorMapper.toSseErrorEvent(e);
                        String json = objectMapper.writeValueAsString(errorEvent);
                        return Flux.just(json);
                    } catch (Exception serEx) {
                        logger.log(Level.SEVERE, "streamAsk() failed to serialize streaming error event", serEx);
                        return Flux.empty();
                    }
                }));
    }

    /**
     * Produces sources and done events for SSE stream.
     */
    private Publisher<String> produceSourcesAndDone(List<SseSources> sources, String conversationId) {
        try {
            logger.info(() -> "produceSourcesAndDone() CALLED — sources=" + (sources != null ? sources.size() : "null")
                    + " conversationId=" + conversationId);

            if (objectMapper == null) {
                logger.severe("produceSourcesAndDone() ERROR: objectMapper is null!");
                return Flux.error(new IllegalStateException("ObjectMapper is null in produceSourcesAndDone"));
            }

            SseSourcesEvent sourcesEvent = new SseSourcesEvent(sources);
            final String sourcesJson = objectMapper.writeValueAsString(sourcesEvent);

            SseDoneEvent doneEvent = new SseDoneEvent(conversationId);
            final String doneJson = objectMapper.writeValueAsString(doneEvent);

            Publisher<String> result = Flux.just(sourcesJson, doneJson);
            logger.info("produceSourcesAndDone() RETURNING Flux with 2 events");
            return result;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "produceSourcesAndDone() EXCEPTION: Failed to serialize sources or done event", e);
            return Flux.error(e);
        }
    }

    private RagContexts buildRagContexts(String question, String cityId, String conversationId, String operationName) {
        long detectionStart = System.nanoTime();
        ContextRequirements requirements = procedureContextService.detectContextRequirements(question, cityId);
        long detectionDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - detectionStart);

        logger.fine(() -> operationName + " context detection — conversationId=" + conversationId
                + " procedure=" + requirements.needsProcedureContext()
                + " event=" + requirements.needsEventContext()
                + " news=" + requirements.needsNewsContext()
                + " durationMs=" + detectionDurationMs);

        if (!requirements.needsProcedureContext() && !requirements.needsEventContext()
                && !requirements.needsNewsContext()) {
            return new RagContexts(null, null, null, false);
        }

        if (requirements.needsNewsContext()) {
            NewsContextResult newsContext = safelyBuildNewsContext(question, cityId, conversationId, operationName);
            logger.fine(() -> operationName + " news retrieval completed — conversationId=" + conversationId
                    + " city=" + cityId
                    + " hitCount=" + (newsContext != null && newsContext.getSources() != null
                            ? newsContext.getSources().size()
                            : 0));
            return new RagContexts(null, null, newsContext, true);
        }

        if (requirements.needsProcedureContext() && requirements.needsEventContext()) {
            long ragStart = System.nanoTime();
            CompletableFuture<ProcedureContextResult> procedureFuture = procedureContextService
                    .buildProcedureContextResultAsync(question, cityId, ragExecutor)
                    .exceptionally(ex -> {
                        logRagFailure(operationName, conversationId, cityId, "procedure", ex);
                        return null;
                    });
            CompletableFuture<ProcedureContextService.EventContextResult> eventFuture = procedureContextService
                    .buildEventContextResultAsync(question, cityId, ragExecutor)
                    .exceptionally(ex -> {
                        logRagFailure(operationName, conversationId, cityId, "event", ex);
                        return null;
                    });

            ProcedureContextResult procedureContext = procedureFuture.join();
            ProcedureContextService.EventContextResult eventContext = eventFuture.join();

            long ragDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ragStart);
            logger.fine(() -> operationName + " RAG build completed — conversationId=" + conversationId
                    + " mode=bounded-parallel durationMs=" + ragDurationMs);
            return new RagContexts(procedureContext, eventContext, null, false);
        }

        if (requirements.needsProcedureContext()) {
            ProcedureContextResult procedureContext = safelyBuildProcedureContext(question, cityId, conversationId,
                    operationName);
            return new RagContexts(procedureContext, null, null, false);
        }

        ProcedureContextService.EventContextResult eventContext = safelyBuildEventContext(question, cityId,
                conversationId, operationName);
        return new RagContexts(null, eventContext, null, false);
    }

    private NewsContextResult safelyBuildNewsContext(String question, String cityId, String conversationId,
            String operationName) {
        long start = System.nanoTime();
        try {
            NewsContextResult result = procedureContextService.buildNewsContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            logger.fine(() -> operationName + " RAG build completed — conversationId=" + conversationId
                    + " mode=news-only durationMs=" + durationMs);
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "news", e);
            return null;
        }
    }

    private ProcedureContextResult safelyBuildProcedureContext(String question, String cityId, String conversationId,
            String operationName) {
        long start = System.nanoTime();
        try {
            ProcedureContextResult result = procedureContextService.buildProcedureContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            logger.fine(() -> operationName + " RAG build completed — conversationId=" + conversationId
                    + " mode=procedure-only durationMs=" + durationMs);
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "procedure", e);
            return null;
        }
    }

    private ProcedureContextService.EventContextResult safelyBuildEventContext(String question, String cityId,
            String conversationId, String operationName) {
        long start = System.nanoTime();
        try {
            ProcedureContextService.EventContextResult result = procedureContextService.buildEventContextResult(
                    question,
                    cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            logger.fine(() -> operationName + " RAG build completed — conversationId=" + conversationId
                    + " mode=event-only durationMs=" + durationMs);
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "event", e);
            return null;
        }
    }

    private void logRagFailure(String operationName, String conversationId, String cityId, String contextType,
            Throwable failure) {
        Throwable rootCause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        logger.log(Level.WARNING,
                operationName + " RAG build failed — conversationId=" + conversationId
                        + " city=" + cityId + " contextType=" + contextType
                        + " error=" + rootCause.getMessage(),
                rootCause);
    }

    private ExecutorService createRagExecutor() {
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "openrouter-rag");
            thread.setDaemon(true);
            return thread;
        });
    }

    private AskStreamResult buildFallbackStreamResult(String fallbackMessage, String conversationId, String question) {
        try {
            SseChunkEvent chunk = new SseChunkEvent(fallbackMessage);
            SseSourcesEvent sourcesEvent = new SseSourcesEvent(List.of());
            SseDoneEvent doneEvent = new SseDoneEvent(conversationId);

            String chunkJson = objectMapper.writeValueAsString(chunk);
            String sourcesJson = objectMapper.writeValueAsString(sourcesEvent);
            String doneJson = objectMapper.writeValueAsString(doneEvent);

            if (conversationId != null && !conversationId.isBlank()) {
                conversationService.updateConversationHistory(conversationId, question, fallbackMessage);
            }

            return new AskStreamResult.Success(Flux.just(chunkJson, sourcesJson, doneJson));
        } catch (Exception e) {
            logger.log(Level.SEVERE, "streamAsk() failed to serialize fallback news response", e);
            return new AskStreamResult.Error(AskStreamErrorMapper.toResponseDto(e));
        }
    }

    private String buildNoNewsFoundMessage(String detectedLanguage, String cityId) {
        String cityDisplayName = RedactPromptBuilder.resolveCityDisplayName(cityId);
        if ("CA".equals(detectedLanguage)) {
            return "No he trobat noticies recents relacionades amb aquesta consulta a " + cityDisplayName + ".";
        }
        if ("ES".equals(detectedLanguage)) {
            return "No he encontrado noticias recientes relacionadas con esa consulta en " + cityDisplayName + ".";
        }
        return "I could not find related recent news about that in " + cityDisplayName + ".";
    }

    private String buildEventDateWindowClarificationMessage(String detectedLanguage) {
        if ("CA".equals(detectedLanguage)) {
            return "Per ajudar-te amb esdeveniments, indica un interval de dates (per exemple: aquesta setmana, abril, o del 10/04 al 15/04).";
        }
        if ("ES".equals(detectedLanguage)) {
            return "Para ayudarte con eventos, indícame un rango de fechas (por ejemplo: esta semana, abril, o del 10/04 al 15/04).";
        }
        return "To help with events, please provide a date window (for example: this week, April, or from 10/04 to 15/04).";
    }

    private record RagContexts(ProcedureContextResult procedureContext,
            ProcedureContextService.EventContextResult eventContext,
            NewsContextResult newsContext,
            boolean newsIntent) {
    }

}
