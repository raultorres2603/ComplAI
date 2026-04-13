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
import cat.complai.openrouter.services.procedure.ProcedureContextService.CityInfoContextResult;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Primary implementation of {@link IOpenRouterService}.
 *
 * <p>Orchestrates RAG context retrieval, conversation history management, AI HTTP calls,
 * response caching, and PDF generation. Delegates to focused helper services rather than
 * containing all logic inline.
 */
/**
 * Primary implementation of {@link IOpenRouterService}.
 *
 * <p>
 * Orchestrates RAG context retrieval, conversation history management, AI HTTP
 * calls,
 * response caching, and PDF generation. Delegates to focused helper services
 * rather than
 * containing all logic inline.
 */
@Singleton
public class OpenRouterServices implements IOpenRouterService {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterServices.class);
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

    /**
     * Validates and adds sources to the merged list, logging warnings for missing
     * URLs.
     * Missing URLs do not prevent the source from being added — logging only.
     * 
     * @param mergedSources  The list to add sources to
     * @param contextSources The sources from a context (procedure, event, news,
     *                       etc.)
     * @param contextType    The type of context (for logging): "procedure",
     *                       "event", "news", "city_info"
     */
    private void validateAndAddSources(List<Source> mergedSources, List<Source> contextSources, String contextType) {
        if (contextSources == null || contextSources.isEmpty()) {
            return;
        }

        for (Source source : contextSources) {
            // Check for missing URL and log warning
            if (source.getUrl() == null || source.getUrl().isBlank()) {
                String title = source.getTitle() != null ? source.getTitle() : "<no title>";
                log.warn("Missing URL for {} item: {}", contextType, title);
            }
            // Always add the source, even if URL is missing
            mergedSources.add(source);
        }
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
        log.info("ask() called — conversationId={} inputLength={} city={}", conversationId, inputLength, cityId);

        var validationError = validationService.validateQuestion(question);
        if (validationError.isPresent()) {
            log.debug("ask() rejected — reason={} conversationId={}", validationError.get().getError(), conversationId);
            return validationError.get();
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        String detectedLanguage = cat.complai.openrouter.helpers.LanguageDetector.detect(question);

        if (procedureContextService.requiresEventDateWindowClarification(question, cityId)) {
            String clarificationMessage = buildEventDateWindowClarificationMessage(detectedLanguage);
            if (conversationId != null && !conversationId.isBlank()) {
                conversationService.updateConversationHistory(conversationId, question, clarificationMessage);
            }
            log.info("ask() event date-window clarification triggered - conversationId={} city={}", conversationId,
                    cityId);
            return new OpenRouterResponseDto(true, clarificationMessage, null, 200, OpenRouterErrorCode.NONE, null,
                    List.of());
        }

        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId, detectedLanguage)));

        RagContexts ragContexts = buildRagContexts(question, cityId, conversationId, "ask()");
        ProcedureContextResult procCtx = ragContexts.procedureContext();
        ProcedureContextService.EventContextResult eventCtx = ragContexts.eventContext();
        NewsContextResult newsCtx = ragContexts.newsContext();
        CityInfoContextResult cityInfoCtx = ragContexts.cityInfoContext();

        if (ragContexts.newsIntent() && (newsCtx == null || newsCtx.getSources().isEmpty())) {
            String fallbackMessage = buildNoNewsFoundMessage(detectedLanguage, cityId);
            if (conversationId != null && !conversationId.isBlank()) {
                conversationService.updateConversationHistory(conversationId, question, fallbackMessage);
            }
            log.info("ask() news fallback used - conversationId={} city={}", conversationId, cityId);
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

        if (cityInfoCtx != null && cityInfoCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", cityInfoCtx.getContextBlock()));
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

        log.debug(
                "ask() CONTEXT METRICS — systemTokens={} historyTokens={} userTokens={} totalTokens={} historyTurns={} conversationId={}",
                systemTokens, historyTokens, userTokens, totalTokens, historyTurns, conversationId);

        log.debug("ask() messages prepared — messageCount={} conversationId={}", messages.size(), conversationId);

        // Calculate context hashes for caching (deterministic, reproducible)
        long procContextHash = computeSourcesHash(procCtx != null ? procCtx.getSources() : List.of());
        List<Source> eventAndNewsSourcesForHash = new ArrayList<>();
        if (eventCtx != null && eventCtx.getSources() != null) {
            eventAndNewsSourcesForHash.addAll(eventCtx.getSources());
        }
        if (newsCtx != null && newsCtx.getSources() != null) {
            eventAndNewsSourcesForHash.addAll(newsCtx.getSources());
        }
        if (cityInfoCtx != null && cityInfoCtx.getSources() != null) {
            eventAndNewsSourcesForHash.addAll(cityInfoCtx.getSources());
        }
        long eventContextHash = computeSourcesHash(eventAndNewsSourcesForHash);

        OpenRouterResponseDto response = aiResponseService.callOpenRouterAndExtract(messages, cityId, procContextHash,
                eventContextHash);

        // Collect all sources WITHOUT deduplication - symmetric handling
        // Procedure and event sources are treated equally at collection time
        List<Source> mergedSources = new ArrayList<>();
        validateAndAddSources(mergedSources, procCtx != null ? procCtx.getSources() : null, "procedure");
        validateAndAddSources(mergedSources, eventCtx != null ? eventCtx.getSources() : null, "event");
        validateAndAddSources(mergedSources, newsCtx != null ? newsCtx.getSources() : null, "news");
        validateAndAddSources(mergedSources, cityInfoCtx != null ? cityInfoCtx.getSources() : null, "city_info");

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
        log.info("redactComplaint() called — conversationId={} inputLength={} format={} identityProvided={} city={}",
                conversationId, inputLength, format, identityProvided, cityId);

        var validationError = validationService.validateRedactInput(complaint);
        if (validationError.isPresent()) {
            log.debug("redactComplaint() rejected — reason={} conversationId={}", validationError.get().getError(),
                    conversationId);
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
                log.debug("redactComplaint() resumed stored complaint — conversationId={} originalLength={}",
                        conversationId, originalComplaint.length());
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
                log.debug("redactComplaint() saved complaint for identity follow-up — conversationId={}",
                        conversationId);
            }
            userPrompt = promptBuilder.buildRedactPromptRequestingIdentity(complaint, identity, cityId);
        }

        messages.add(Map.of("role", "user", "content", userPrompt));

        log.debug("redactComplaint() messages prepared — messageCount={} identityComplete={} conversationId={}",
                messages.size(), identityComplete, conversationId);

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
                log.error("streamAsk() failed to serialize validation error", e);
                return new AskStreamResult.Error(AskStreamErrorMapper.toResponseDto(e));
            }
        }

        // 2. Single-language system message + RAG
        String detectedLanguage = LanguageDetector.detect(question);

        if (procedureContextService.requiresEventDateWindowClarification(question, cityId)) {
            String clarificationMessage = buildEventDateWindowClarificationMessage(detectedLanguage);
            log.info("streamAsk() event date-window clarification triggered - conversationId={} city={}",
                    conversationId, cityId);
            return buildFallbackStreamResult(clarificationMessage, conversationId, question);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId, detectedLanguage)));

        RagContexts ragContexts = buildRagContexts(question, cityId, conversationId, "streamAsk()");
        ProcedureContextResult procCtx = ragContexts.procedureContext();
        ProcedureContextService.EventContextResult eventCtx = ragContexts.eventContext();
        NewsContextResult newsCtx = ragContexts.newsContext();
        CityInfoContextResult cityInfoCtx = ragContexts.cityInfoContext();

        if (ragContexts.newsIntent() && (newsCtx == null || newsCtx.getSources().isEmpty())) {
            String fallbackMessage = buildNoNewsFoundMessage(detectedLanguage, cityId);
            log.info("streamAsk() news fallback used - conversationId={} city={}", conversationId, cityId);
            return buildFallbackStreamResult(fallbackMessage, conversationId, question);
        }

        if (procCtx != null && procCtx.getContextBlock() != null)
            messages.add(Map.of("role", "system", "content", procCtx.getContextBlock()));
        if (eventCtx != null && eventCtx.getContextBlock() != null)
            messages.add(Map.of("role", "system", "content", eventCtx.getContextBlock()));
        if (newsCtx != null && newsCtx.getContextBlock() != null)
            messages.add(Map.of("role", "system", "content", newsCtx.getContextBlock()));
        if (cityInfoCtx != null && cityInfoCtx.getContextBlock() != null)
            messages.add(Map.of("role", "system", "content", cityInfoCtx.getContextBlock()));

        var history = conversationService.getConversationHistory(conversationId);
        conversationService.addToMessages(messages, history);
        messages.add(Map.of("role", "user", "content", question != null ? question.trim() : ""));

        // 4. Collect sources from RAG context with URL validation
        List<Source> validatedSources = new ArrayList<>();
        validateAndAddSources(validatedSources, procCtx != null ? procCtx.getSources() : null, "procedure");
        validateAndAddSources(validatedSources, eventCtx != null ? eventCtx.getSources() : null, "event");
        validateAndAddSources(validatedSources, newsCtx != null ? newsCtx.getSources() : null, "news");
        validateAndAddSources(validatedSources, cityInfoCtx != null ? cityInfoCtx.getSources() : null, "city_info");

        List<SseSources> allSources = validatedSources.stream()
                .map(s -> new SseSources(s.getTitle(), s.getUrl()))
                .collect(Collectors.toList());

        // 5. Stream chunks, then emit sources, then done
        final String capturedQuestion = question;
        final String capturedCityId = cityId;
        final String capturedConvId = conversationId;
        final StringBuilder assembled = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean hasEmitted = new java.util.concurrent.atomic.AtomicBoolean(
                false);

        log.debug("streamAsk() preparing to stream — conversationId={} sources={}", capturedConvId, allSources.size());

        // Verify objectMapper is not null before proceeding
        if (objectMapper == null) {
            log.error("streamAsk() ERROR: objectMapper is null! This should never happen.");
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
                    log.debug("streamAsk() chunk delta processed");
                })
                .map(delta -> {
                    // Serialize each chunk delta to JSON
                    try {
                        SseChunkEvent chunk = new SseChunkEvent(delta);
                        String serialized = objectMapper.writeValueAsString(chunk);
                        log.debug("streamAsk() emitting chunk event serialized");
                        return serialized;
                    } catch (Exception e) {
                        log.error("Failed to serialize chunk", e);
                        throw new RuntimeException("Chunk serialization failed", e);
                    }
                })
                .doOnComplete(() -> {
                    log.info("First Flux completed");
                })
                .concatWith(produceSourcesAndDone(allSources, capturedConvId))
                .doOnNext(event -> log.debug("streamAsk() emitting event"))
                .doOnComplete(() -> {
                    String fullText = assembled.toString();
                    if (capturedConvId != null && !capturedConvId.isBlank() && !fullText.isBlank()) {
                        conversationService.updateConversationHistory(capturedConvId, capturedQuestion, fullText);
                    }
                    log.info("streamAsk() completed — conversationId={} assembledLength={} city={}",
                            capturedConvId, fullText.length(), capturedCityId);
                })
                .doOnError(e -> {
                    log.error("streamAsk() error during streaming — conversationId={}", capturedConvId, e);
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
                        log.error("streamAsk() failed to serialize streaming error event", serEx);
                        return Flux.empty();
                    }
                }));
    }

    /**
     * Produces sources and done events for SSE stream.
     */
    private Publisher<String> produceSourcesAndDone(List<SseSources> sources, String conversationId) {
        try {
            log.info("produceSourcesAndDone() CALLED — sources={} conversationId={}",
                    sources != null ? sources.size() : "null", conversationId);

            if (objectMapper == null) {
                log.error("produceSourcesAndDone() ERROR: objectMapper is null!");
                return Flux.error(new IllegalStateException("ObjectMapper is null in produceSourcesAndDone"));
            }

            SseSourcesEvent sourcesEvent = new SseSourcesEvent(sources);
            final String sourcesJson = objectMapper.writeValueAsString(sourcesEvent);

            SseDoneEvent doneEvent = new SseDoneEvent(conversationId);
            final String doneJson = objectMapper.writeValueAsString(doneEvent);

            Publisher<String> result = Flux.just(sourcesJson, doneJson);
            log.info("produceSourcesAndDone() RETURNING Flux with 2 events");
            return result;
        } catch (Exception e) {
            log.error("produceSourcesAndDone() EXCEPTION: Failed to serialize sources or done event", e);
            return Flux.error(e);
        }
    }

    private RagContexts buildRagContexts(String question, String cityId, String conversationId, String operationName) {
        long detectionStart = System.nanoTime();
        ContextRequirements requirements = procedureContextService.detectContextRequirements(question, cityId);
        long detectionDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - detectionStart);

        log.debug(
                "{} context detection — conversationId={} procedure={} event={} news={} cityInfo={} durationMs={}",
                operationName, conversationId,
                requirements.needsProcedureContext(),
                requirements.needsEventContext(),
                requirements.needsNewsContext(),
                requirements.needsCityInfoContext(),
                detectionDurationMs);

        if (!requirements.needsProcedureContext() && !requirements.needsEventContext()
                && !requirements.needsNewsContext() && !requirements.needsCityInfoContext()) {
            return new RagContexts(null, null, null, null, false);
        }

        if (requirements.needsNewsContext()) {
            NewsContextResult newsContext = safelyBuildNewsContext(question, cityId, conversationId, operationName);
            log.debug("{} news retrieval completed — conversationId={} city={} hitCount={}",
                    operationName, conversationId, cityId,
                    newsContext != null && newsContext.getSources() != null
                            ? newsContext.getSources().size()
                            : 0);
            return new RagContexts(null, null, newsContext, null, true);
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
            log.debug("{} RAG build completed — conversationId={} mode=bounded-parallel durationMs={}",
                    operationName, conversationId, ragDurationMs);
            return new RagContexts(procedureContext, eventContext, null, null, false);
        }

        if (requirements.needsProcedureContext()) {
            ProcedureContextResult procedureContext = safelyBuildProcedureContext(question, cityId, conversationId,
                    operationName);
            return new RagContexts(procedureContext, null, null, null, false);
        }

        if (requirements.needsEventContext()) {
            ProcedureContextService.EventContextResult eventContext = safelyBuildEventContext(question, cityId,
                    conversationId, operationName);
            return new RagContexts(null, eventContext, null, null, false);
        }

        if (requirements.needsCityInfoContext()) {
            CityInfoContextResult cityInfoContext = safelyBuildCityInfoContext(question, cityId, conversationId,
                    operationName);
            return new RagContexts(null, null, null, cityInfoContext, false);
        }

        return new RagContexts(null, null, null, null, false);
    }

    private NewsContextResult safelyBuildNewsContext(String question, String cityId, String conversationId,
            String operationName) {
        long start = System.nanoTime();
        try {
            NewsContextResult result = procedureContextService.buildNewsContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.debug("{} RAG build completed — conversationId={} mode=news-only durationMs={}",
                    operationName, conversationId, durationMs);
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
            log.debug("{} RAG build completed — conversationId={} mode=procedure-only durationMs={}",
                    operationName, conversationId, durationMs);
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
            log.debug("{} RAG build completed — conversationId={} mode=event-only durationMs={}",
                    operationName, conversationId, durationMs);
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "event", e);
            return null;
        }
    }

    private CityInfoContextResult safelyBuildCityInfoContext(String question, String cityId, String conversationId,
            String operationName) {
        long start = System.nanoTime();
        try {
            CityInfoContextResult result = procedureContextService.buildCityInfoContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.debug("{} RAG build completed — conversationId={} mode=cityinfo-only durationMs={}",
                    operationName, conversationId, durationMs);
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "cityinfo", e);
            return null;
        }
    }

    private void logRagFailure(String operationName, String conversationId, String cityId, String contextType,
            Throwable failure) {
        Throwable rootCause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        log.warn("{} RAG build failed — conversationId={} city={} contextType={} error={}",
                operationName, conversationId, cityId, contextType, rootCause.getMessage(), rootCause);
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
            log.error("streamAsk() failed to serialize fallback news response", e);
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
            CityInfoContextResult cityInfoContext,
            boolean newsIntent) {
    }

}
