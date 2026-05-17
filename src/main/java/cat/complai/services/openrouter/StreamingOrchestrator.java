package cat.complai.services.openrouter;

import cat.complai.utilities.http.HttpWrapper;
import cat.complai.dto.http.OpenRouterStreamStartResult;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
import cat.complai.services.openrouter.validation.InputValidationService;
import cat.complai.dto.openrouter.AskStreamResult;
import cat.complai.dto.openrouter.Source;
import cat.complai.dto.openrouter.sse.SseChunkEvent;
import cat.complai.dto.openrouter.sse.SseSources;
import cat.complai.dto.openrouter.sse.SseSourcesEvent;
import cat.complai.dto.openrouter.sse.SseDoneEvent;
import cat.complai.dto.openrouter.sse.SseErrorEvent;
import cat.complai.helpers.openrouter.AskStreamErrorMapper;
import cat.complai.helpers.openrouter.LanguageDetector;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.helpers.openrouter.SseChunkParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.core.annotation.Nullable;
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
 * Handles SSE (Server-Sent Events) streaming for the {@code /complai/ask}
 * endpoint. Manages the entire streaming lifecycle: validation, RAG context
 * building, AI streaming via HTTP, and SSE event production.
 *
 * <p>This class was extracted from {@code OpenRouterServices} during the
 * god-class split.</p>
 */
@Singleton
public class StreamingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StreamingOrchestrator.class);

    private final InputValidationService validationService;
    private final ConversationManagementService conversationService;
    private final RagContextBuilder ragContextBuilder;
    private final ClarificationService clarificationService;
    private final IntentDetector intentDetector;
    private final RedactPromptBuilder promptBuilder;
    private final HttpWrapper httpWrapper;
    private final ObjectMapper objectMapper;
    private final ExecutorService ragExecutor;

    @Inject
    public StreamingOrchestrator(InputValidationService validationService,
                                  ConversationManagementService conversationService,
                                  RagContextBuilder ragContextBuilder,
                                  ClarificationService clarificationService,
                                  IntentDetector intentDetector,
                                  RedactPromptBuilder promptBuilder,
                                  HttpWrapper httpWrapper,
                                  ObjectMapper objectMapper) {
        this.validationService = validationService;
        this.conversationService = conversationService;
        this.ragContextBuilder = ragContextBuilder;
        this.clarificationService = clarificationService;
        this.intentDetector = intentDetector;
        this.promptBuilder = promptBuilder;
        this.httpWrapper = httpWrapper;
        this.objectMapper = objectMapper;
        this.ragExecutor = createRagExecutor();
    }

    /**
     * Streams an answer to the given question as SSE events.
     */
    public AskStreamResult streamAsk(String question, String conversationId, String cityId) {
        // 1. Validate input
        var validationError = validationService.validateQuestion(question);
        if (validationError.isPresent()) {
            String errorText = validationError.get().getError();
            errorText = errorText != null ? errorText : "Invalid input.";
            try {
                SseErrorEvent errorEvent = new SseErrorEvent(errorText, cat.complai.dto.openrouter.OpenRouterErrorCode.VALIDATION.getCode());
                String json = objectMapper.writeValueAsString(errorEvent);
                return new AskStreamResult.Success(Flux.just(json));
            } catch (Exception e) {
                log.error("streamAsk() failed to serialize validation error", e);
                return new AskStreamResult.Error(AskStreamErrorMapper.toResponseDto(e));
            }
        }

        String detectedLanguage = RedactPromptBuilder.normalizeLanguageCode(LanguageDetector.detect(question));

        // 2. Check event date-window clarification
        if (intentDetector.requiresEventDateWindowClarification(question, cityId)) {
            String clarificationMessage = buildEventDateWindowClarificationMessage(detectedLanguage);
            log.info("streamAsk() event date-window clarification triggered - conversationId={} city={}",
                    conversationId, cityId);
            return buildFallbackStreamResult(clarificationMessage, conversationId, question);
        }

        // 3. Check pending clarification resolution
        if (conversationId != null && !conversationId.isBlank()) {
            AskStreamResult resolved = tryResolvePendingClarification(
                    question, conversationId, cityId, detectedLanguage);
            if (resolved != null) {
                return resolved;
            }
        }

        // 4. Check for procedure ambiguity
        if (conversationId != null && !conversationId.isBlank()) {
            Optional<ProcedureAmbiguityResult> ambiguity =
                    clarificationService.detectProcedureAmbiguity(question, cityId);
            if (ambiguity.isPresent()) {
                List<ConversationManagementService.ClarificationCandidate> candidates =
                        ambiguity.get().candidates();
                conversationService.storePendingClarification(conversationId, candidates);
                List<String> titles = candidates.stream().map(c -> c.title()).toList();
                String clarificationMsg = promptBuilder.buildProcedureClarificationMessage(
                        titles, detectedLanguage, cityId);
                log.info("streamAsk() procedure ambiguity clarification triggered — conversationId={} candidateCount={}",
                        conversationId, candidates.size());
                return buildFallbackStreamResult(clarificationMsg, conversationId, question);
            }
        }

        // 5. Build RAG contexts and messages
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId, detectedLanguage)));

        RagContexts ragContexts = buildRagContexts(question, cityId, conversationId, "streamAsk()");
        ProcedureContextResult procCtx = ragContexts.procedureContext();
        EventContextResult eventCtx = ragContexts.eventContext();
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

        // 6. Collect sources
        List<Source> validatedSources = new ArrayList<>();
        ragContextBuilder.validateAndAddSources(validatedSources, procCtx != null ? procCtx.getSources() : null, "procedure");
        ragContextBuilder.validateAndAddSources(validatedSources, eventCtx != null ? eventCtx.getSources() : null, "event");
        ragContextBuilder.validateAndAddSources(validatedSources, newsCtx != null ? newsCtx.getSources() : null, "news");
        ragContextBuilder.validateAndAddSources(validatedSources, cityInfoCtx != null ? cityInfoCtx.getSources() : null, "city_info");

        List<SseSources> allSources = validatedSources.stream()
                .map(s -> new SseSources(s.getTitle(), s.getUrl()))
                .collect(Collectors.toList());

        // 7. Stream
        final String capturedQuestion = question;
        final String capturedCityId = cityId;
        final String capturedConvId = conversationId;
        final StringBuilder assembled = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean hasEmitted =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        if (objectMapper == null) {
            log.error("streamAsk() ERROR: objectMapper is null!");
            return new AskStreamResult.Error(AskStreamErrorMapper.toResponseDto(
                    new IllegalStateException("ObjectMapper is not properly injected")));
        }

        OpenRouterStreamStartResult streamStartResult = httpWrapper.streamFromOpenRouter(messages);
        if (streamStartResult instanceof OpenRouterStreamStartResult.Error error) {
            return new AskStreamResult.Error(AskStreamErrorMapper.toResponseDto(error.failure()));
        }

        Publisher<String> stream = ((OpenRouterStreamStartResult.Success) streamStartResult).stream();

        return new AskStreamResult.Success(Flux.from(stream)
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
                })
                .map(delta -> {
                    try {
                        SseChunkEvent chunk = new SseChunkEvent(delta);
                        return objectMapper.writeValueAsString(chunk);
                    } catch (Exception e) {
                        log.error("Failed to serialize chunk", e);
                        throw new RuntimeException("Chunk serialization failed", e);
                    }
                })
                .concatWith(produceSourcesAndDone(allSources, capturedConvId))
                .doOnComplete(() -> {
                    String fullText = assembled.toString();
                    if (capturedConvId != null && !capturedConvId.isBlank() && !fullText.isBlank()) {
                        conversationService.updateConversationHistory(capturedConvId, capturedQuestion, fullText);
                    }
                    log.info("streamAsk() completed — conversationId={} assembledLength={} city={}",
                            capturedConvId, fullText.length(), capturedCityId);
                })
                .doOnError(e -> log.error("streamAsk() error during streaming — conversationId={}", capturedConvId, e))
                .onErrorResume(e -> {
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

    // -----------------------------------------------------------------------
    // Private: clarification resolution for streaming
    // -----------------------------------------------------------------------

    @Nullable
    private AskStreamResult tryResolvePendingClarification(
            String question, String conversationId, String cityId, String detectedLanguage) {
        List<ConversationManagementService.ClarificationCandidate> streamPending =
                conversationService.getPendingClarification(conversationId);
        if (streamPending == null || streamPending.isEmpty()) {
            return null;
        }

        conversationService.clearPendingClarification(conversationId);
        java.util.OptionalInt streamResolved = clarificationService.resolveClarificationAnswer(question, streamPending);
        if (streamResolved.isEmpty()) {
            log.info("streamAsk() clarification unresolvable — falling back to normal RAG conversationId={}",
                    conversationId);
            return null;
        }

        ConversationManagementService.ClarificationCandidate streamChosen =
                streamPending.get(streamResolved.getAsInt());
        ProcedureContextResult streamResolvedCtx =
                ragContextBuilder.buildProcedureContextResultForId(streamChosen.procedureId(), cityId);

        List<Map<String, Object>> streamResolvedMsgs = new ArrayList<>();
        streamResolvedMsgs.add(Map.of("role", "system", "content",
                promptBuilder.getSystemMessage(cityId, detectedLanguage)));
        if (streamResolvedCtx != null && streamResolvedCtx.getContextBlock() != null) {
            streamResolvedMsgs.add(Map.of("role", "system", "content", streamResolvedCtx.getContextBlock()));
        }
        var streamResolvedHistory = conversationService.getConversationHistory(conversationId);
        conversationService.addToMessages(streamResolvedMsgs, streamResolvedHistory);
        streamResolvedMsgs.add(Map.of("role", "user",
                "content", question != null ? question.trim() : ""));

        List<Source> streamResolvedSrcs = new ArrayList<>();
        ragContextBuilder.validateAndAddSources(streamResolvedSrcs,
                streamResolvedCtx != null ? streamResolvedCtx.getSources() : null, "procedure");
        List<SseSources> streamResolvedAllSources = streamResolvedSrcs.stream()
                .map(s -> new SseSources(s.getTitle(), s.getUrl()))
                .collect(Collectors.toList());

        final String srCapturedQuestion = question;
        final String srCapturedConvId = conversationId;
        final StringBuilder srAssembled = new StringBuilder();
        final java.util.concurrent.atomic.AtomicBoolean srHasEmitted =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        log.info("streamAsk() clarification resolved — conversationId={} procedureId={}",
                conversationId, streamChosen.procedureId());

        OpenRouterStreamStartResult srStreamStart = httpWrapper.streamFromOpenRouter(streamResolvedMsgs);
        if (srStreamStart instanceof OpenRouterStreamStartResult.Error srErr) {
            return new AskStreamResult.Error(AskStreamErrorMapper.toResponseDto(srErr.failure()));
        }
        Publisher<String> srStream = ((OpenRouterStreamStartResult.Success) srStreamStart).stream();

        return new AskStreamResult.Success(Flux.from(srStream)
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
                    srAssembled.append(delta);
                    srHasEmitted.set(true);
                })
                .map(delta -> {
                    try {
                        SseChunkEvent chunkEvt = new SseChunkEvent(delta);
                        return objectMapper.writeValueAsString(chunkEvt);
                    } catch (Exception e) {
                        log.error("Failed to serialize resolved chunk", e);
                        throw new RuntimeException("Chunk serialization failed", e);
                    }
                })
                .concatWith(produceSourcesAndDone(streamResolvedAllSources, srCapturedConvId))
                .doOnComplete(() -> {
                    String fullText = srAssembled.toString();
                    if (srCapturedConvId != null && !srCapturedConvId.isBlank() && !fullText.isBlank()) {
                        conversationService.updateConversationHistory(
                                srCapturedConvId, srCapturedQuestion, fullText);
                    }
                    log.info("streamAsk() resolved clarification completed — conversationId={}",
                            srCapturedConvId);
                })
                .doOnError(e -> log.error(
                        "streamAsk() error during resolved streaming — conversationId={}", srCapturedConvId, e))
                .onErrorResume(e -> {
                    if (!srHasEmitted.get()) {
                        return Flux.error(e);
                    }
                    try {
                        SseErrorEvent errorEvent = AskStreamErrorMapper.toSseErrorEvent(e);
                        String json = objectMapper.writeValueAsString(errorEvent);
                        return Flux.just(json);
                    } catch (Exception serEx) {
                        log.error("streamAsk() failed to serialize resolved error event", serEx);
                        return Flux.empty();
                    }
                }));
    }

    // -----------------------------------------------------------------------
    // RAG context building
    // -----------------------------------------------------------------------

    private RagContexts buildRagContexts(String question, String cityId, String conversationId, String operationName) {
        long detectionStart = System.nanoTime();
        ContextRequirements requirements = intentDetector.detectContextRequirements(question, cityId);
        long detectionDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - detectionStart);

        log.debug("{} context detection — conversationId={} procedure={} event={} news={} cityInfo={} durationMs={}",
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
            return new RagContexts(null, null, newsContext, null, true);
        }

        if (requirements.needsProcedureContext() && requirements.needsEventContext()) {
            long ragStart = System.nanoTime();
            CompletableFuture<ProcedureContextResult> procedureFuture = ragContextBuilder
                    .buildProcedureContextResultAsync(question, cityId, ragExecutor)
                    .exceptionally(ex -> {
                        logRagFailure(operationName, conversationId, cityId, "procedure", ex);
                        return null;
                    });
            CompletableFuture<EventContextResult> eventFuture = ragContextBuilder
                    .buildEventContextResultAsync(question, cityId, ragExecutor)
                    .exceptionally(ex -> {
                        logRagFailure(operationName, conversationId, cityId, "event", ex);
                        return null;
                    });
            ProcedureContextResult procedureContext = procedureFuture.join();
            EventContextResult eventContext = eventFuture.join();

            long ragDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ragStart);
            log.debug("{} RAG build completed — conversationId={} mode=bounded-parallel durationMs={}",
                    operationName, conversationId, ragDurationMs);
            return new RagContexts(procedureContext, eventContext, null, null, false);
        }

        if (requirements.needsProcedureContext()) {
            ProcedureContextResult procedureContext = safelyBuildProcedureContext(
                    question, cityId, conversationId, operationName);
            return new RagContexts(procedureContext, null, null, null, false);
        }

        if (requirements.needsEventContext()) {
            EventContextResult eventContext = safelyBuildEventContext(
                    question, cityId, conversationId, operationName);
            return new RagContexts(null, eventContext, null, null, false);
        }

        if (requirements.needsCityInfoContext()) {
            CityInfoContextResult cityInfoContext = safelyBuildCityInfoContext(
                    question, cityId, conversationId, operationName);
            return new RagContexts(null, null, null, cityInfoContext, false);
        }

        return new RagContexts(null, null, null, null, false);
    }

    @Nullable
    private NewsContextResult safelyBuildNewsContext(String question, String cityId,
                                                      String conversationId, String operationName) {
        long start = System.nanoTime();
        try {
            NewsContextResult result = ragContextBuilder.buildNewsContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.debug("{} RAG build completed — conversationId={} mode=news-only durationMs={}",
                    operationName, conversationId, durationMs);
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "news", e);
            return null;
        }
    }

    @Nullable
    private ProcedureContextResult safelyBuildProcedureContext(String question, String cityId,
                                                                String conversationId, String operationName) {
        long start = System.nanoTime();
        try {
            ProcedureContextResult result = ragContextBuilder.buildProcedureContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.debug("{} RAG build completed — conversationId={} mode=procedure-only durationMs={}",
                    operationName, conversationId, durationMs);
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "procedure", e);
            return null;
        }
    }

    @Nullable
    private EventContextResult safelyBuildEventContext(String question, String cityId,
                                                        String conversationId, String operationName) {
        long start = System.nanoTime();
        try {
            EventContextResult result = ragContextBuilder.buildEventContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.debug("{} RAG build completed — conversationId={} mode=event-only durationMs={}",
                    operationName, conversationId, durationMs);
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "event", e);
            return null;
        }
    }

    @Nullable
    private CityInfoContextResult safelyBuildCityInfoContext(String question, String cityId,
                                                              String conversationId, String operationName) {
        long start = System.nanoTime();
        try {
            CityInfoContextResult result = ragContextBuilder.buildCityInfoContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            log.debug("{} RAG build completed — conversationId={} mode=cityinfo-only durationMs={}",
                    operationName, conversationId, durationMs);
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "cityinfo", e);
            return null;
        }
    }

    private void logRagFailure(String operationName, String conversationId, String cityId,
                                String contextType, Throwable failure) {
        Throwable rootCause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        log.warn("{} RAG build failed — conversationId={} city={} contextType={} error={}",
                operationName, conversationId, cityId, contextType, rootCause.getMessage(), rootCause);
    }

    // -----------------------------------------------------------------------
    // SSE event production
    // -----------------------------------------------------------------------

    private Publisher<String> produceSourcesAndDone(List<SseSources> sources, String conversationId) {
        try {
            SseSourcesEvent sourcesEvent = new SseSourcesEvent(sources);
            final String sourcesJson = objectMapper.writeValueAsString(sourcesEvent);

            SseDoneEvent doneEvent = new SseDoneEvent(conversationId);
            final String doneJson = objectMapper.writeValueAsString(doneEvent);

            return Flux.just(sourcesJson, doneJson);
        } catch (Exception e) {
            log.error("produceSourcesAndDone() EXCEPTION: Failed to serialize sources or done event", e);
            return Flux.error(e);
        }
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

    // -----------------------------------------------------------------------
    // Executor
    // -----------------------------------------------------------------------

    private ExecutorService createRagExecutor() {
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "openrouter-rag-stream");
            thread.setDaemon(true);
            return thread;
        });
    }

    private record RagContexts(ProcedureContextResult procedureContext,
                               EventContextResult eventContext,
                               NewsContextResult newsContext,
                               CityInfoContextResult cityInfoContext,
                               boolean newsIntent) {
    }
}
