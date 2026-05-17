package cat.complai.services.openrouter;

import cat.complai.dto.openrouter.AskStreamResult;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.OutputFormat;
import cat.complai.dto.openrouter.Source;
import cat.complai.helpers.openrouter.LanguageDetector;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.services.openrouter.ai.AiResponseProcessingService;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
import cat.complai.services.openrouter.validation.InputValidationService;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Primary implementation of {@link IOpenRouterService}.
 *
 * <p>Orchestrates RAG context retrieval, conversation history management, AI
 * HTTP calls, response caching, and PDF generation. Delegates intent detection
 * to {@link IntentDetector}, context building to {@link RagContextBuilder},
 * ambiguity handling to {@link ClarificationService}, streaming to
 * {@link StreamingOrchestrator}, and redact workflow to
 * {@link RedactOrchestrator}.
 */
@Singleton
public class OpenRouterServices implements IOpenRouterService {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterServices.class);

    private final InputValidationService validationService;
    private final ConversationManagementService conversationService;
    private final AiResponseProcessingService aiResponseService;
    private final IntentDetector intentDetector;
    private final RagContextBuilder ragContextBuilder;
    private final ClarificationService clarificationService;
    private final StreamingOrchestrator streamingOrchestrator;
    private final RedactOrchestrator redactOrchestrator;
    private final RedactPromptBuilder promptBuilder;
    private final ExecutorService ragExecutor;

    @Inject
    public OpenRouterServices(InputValidationService validationService,
                              ConversationManagementService conversationService,
                              AiResponseProcessingService aiResponseService,
                              IntentDetector intentDetector,
                              RagContextBuilder ragContextBuilder,
                              ClarificationService clarificationService,
                              StreamingOrchestrator streamingOrchestrator,
                              RedactOrchestrator redactOrchestrator,
                              RedactPromptBuilder promptBuilder) {
        this.validationService = validationService;
        this.conversationService = conversationService;
        this.aiResponseService = aiResponseService;
        this.intentDetector = intentDetector;
        this.ragContextBuilder = ragContextBuilder;
        this.clarificationService = clarificationService;
        this.streamingOrchestrator = streamingOrchestrator;
        this.redactOrchestrator = redactOrchestrator;
        this.promptBuilder = promptBuilder;
        this.ragExecutor = createRagExecutor();
    }

    @PreDestroy
    void shutdownRagExecutor() {
        ragExecutor.shutdown();
    }

    // -------------------------------------------------------------------------
    // IOpenRouterService — validateRedactInput
    // -------------------------------------------------------------------------

    @Override
    public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
        return redactOrchestrator.validateRedactInput(complaint);
    }

    // -------------------------------------------------------------------------
    // IOpenRouterService — ask (synchronous)
    // -------------------------------------------------------------------------

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
        String detectedLanguage = RedactPromptBuilder.normalizeLanguageCode(
                LanguageDetector.detect(question));

        if (intentDetector.requiresEventDateWindowClarification(question, cityId)) {
            String clarificationMessage = buildEventDateWindowClarificationMessage(detectedLanguage);
            if (conversationId != null && !conversationId.isBlank()) {
                conversationService.updateConversationHistory(conversationId, question, clarificationMessage);
            }
            log.info("ask() event date-window clarification triggered - conversationId={} city={}", conversationId, cityId);
            return new OpenRouterResponseDto(true, clarificationMessage, null, 200, OpenRouterErrorCode.NONE, null,
                    List.of());
        }

        if (conversationId != null && !conversationId.isBlank()) {
            List<ConversationManagementService.ClarificationCandidate> pending =
                    conversationService.getPendingClarification(conversationId);
            if (pending != null && !pending.isEmpty()) {
                conversationService.clearPendingClarification(conversationId);
                OptionalInt resolved = clarificationService.resolveClarificationAnswer(question, pending);
                if (resolved.isPresent()) {
                    ConversationManagementService.ClarificationCandidate chosen =
                            pending.get(resolved.getAsInt());
                    ProcedureContextResult resolvedCtx =
                            ragContextBuilder.buildProcedureContextResultForId(chosen.procedureId(), cityId);

                    List<Map<String, Object>> resolvedMessages = new ArrayList<>();
                    resolvedMessages.add(Map.of("role", "system", "content",
                            promptBuilder.getSystemMessage(cityId, detectedLanguage)));
                    if (resolvedCtx != null && resolvedCtx.getContextBlock() != null) {
                        resolvedMessages.add(Map.of("role", "system", "content", resolvedCtx.getContextBlock()));
                    }
                    var resolvedHistory = conversationService.getConversationHistory(conversationId);
                    conversationService.addToMessages(resolvedMessages, resolvedHistory);
                    resolvedMessages.add(Map.of("role", "user",
                            "content", question != null ? question.trim() : ""));

                    long resolvedProcHash = computeSourcesHash(
                            resolvedCtx != null ? resolvedCtx.getSources() : List.of());
                    OpenRouterResponseDto resolvedResponse =
                            aiResponseService.callOpenRouterAndExtract(
                                    resolvedMessages, cityId, resolvedProcHash, 0L);

                    List<Source> resolvedSources = new ArrayList<>();
                    ragContextBuilder.validateAndAddSources(resolvedSources,
                            resolvedCtx != null ? resolvedCtx.getSources() : null, "procedure");
                    if (resolvedResponse.isSuccess() && !resolvedSources.isEmpty()) {
                        List<Source> finalSources =
                                ragContextBuilder.deDuplicateAndOrderSources(resolvedSources);
                        resolvedResponse = new OpenRouterResponseDto(
                                resolvedResponse.isSuccess(), resolvedResponse.getMessage(),
                                resolvedResponse.getError(), resolvedResponse.getStatusCode(),
                                resolvedResponse.getErrorCode(), resolvedResponse.getPdfData(),
                                finalSources);
                    }
                    if (resolvedResponse.getMessage() != null) {
                        conversationService.updateConversationHistory(
                                conversationId, question, resolvedResponse.getMessage());
                    }
                    log.info("ask() clarification resolved — conversationId={} procedureId={}",
                            conversationId, chosen.procedureId());
                    return resolvedResponse;
                }
                log.info("ask() clarification unresolvable — falling back to normal RAG conversationId={}",
                        conversationId);
            }
        }

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
                conversationService.updateConversationHistory(conversationId, question, clarificationMsg);
                log.info("ask() procedure ambiguity clarification triggered — conversationId={} candidateCount={}",
                        conversationId, candidates.size());
                return new OpenRouterResponseDto(true, clarificationMsg, null, 200,
                        OpenRouterErrorCode.NONE, null, List.of());
            }
        }

        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId, detectedLanguage)));

        RagContexts ragContexts = buildRagContexts(question, cityId, conversationId, "ask()");
        ProcedureContextResult procCtx = ragContexts.procedureContext();
        EventContextResult eventCtx = ragContexts.eventContext();
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

        if (procCtx != null && procCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", procCtx.getContextBlock()));
        }
        if (eventCtx != null && eventCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", eventCtx.getContextBlock()));
        }
        if (newsCtx != null && newsCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", newsCtx.getContextBlock()));
        }
        if (cityInfoCtx != null && cityInfoCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", cityInfoCtx.getContextBlock()));
        }

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
            Object rawContent = msg.get("content");
            String content = rawContent instanceof String s ? s : null;
            if (content == null)
                continue;
            Object rawRole = msg.get("role");
            String role = rawRole instanceof String s ? s : "";
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

        // Calculate context hashes for caching
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

        OpenRouterResponseDto response = aiResponseService.callOpenRouterAndExtract(
                messages, cityId, procContextHash, eventContextHash);

        // Collect all sources
        List<Source> mergedSources = new ArrayList<>();
        ragContextBuilder.validateAndAddSources(mergedSources,
                procCtx != null ? procCtx.getSources() : null, "procedure");
        ragContextBuilder.validateAndAddSources(mergedSources,
                eventCtx != null ? eventCtx.getSources() : null, "event");
        ragContextBuilder.validateAndAddSources(mergedSources,
                newsCtx != null ? newsCtx.getSources() : null, "news");
        ragContextBuilder.validateAndAddSources(mergedSources,
                cityInfoCtx != null ? cityInfoCtx.getSources() : null, "city_info");

        if (response.isSuccess() && !mergedSources.isEmpty()) {
            List<Source> finalSources = ragContextBuilder.deDuplicateAndOrderSources(mergedSources);
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

    // -------------------------------------------------------------------------
    // IOpenRouterService — redactComplaint (delegates to RedactOrchestrator)
    // -------------------------------------------------------------------------

    @Override
    public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId,
                                                  ComplainantIdentity identity, String cityId) {
        return redactOrchestrator.redactComplaint(complaint, format, conversationId, identity, cityId);
    }

    // -------------------------------------------------------------------------
    // IOpenRouterService — streamAsk (delegates to StreamingOrchestrator)
    // -------------------------------------------------------------------------

    @Override
    public AskStreamResult streamAsk(String question, String conversationId, String cityId) {
        return streamingOrchestrator.streamAsk(question, conversationId, cityId);
    }

    // -----------------------------------------------------------------------
    // Private helpers — RAG context building
    // -----------------------------------------------------------------------

    private RagContexts buildRagContexts(String question, String cityId, String conversationId, String operationName) {
        long detectionStart = System.nanoTime();
        ContextRequirements requirements = intentDetector.detectContextRequirements(question, cityId);
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
    // Private helpers — utilities
    // -----------------------------------------------------------------------

    private ExecutorService createRagExecutor() {
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "openrouter-rag");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private static long computeSourcesHash(List<Source> sources) {
        if (sources == null || sources.isEmpty()) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (Source source : sources) {
            String title = source.getTitle();
            String url = source.getUrl();
            if (title != null)
                sb.append(title).append("|");
            if (url != null)
                sb.append(url).append("|");
        }
        return sb.toString().hashCode();
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
                               EventContextResult eventContext,
                               NewsContextResult newsContext,
                               CityInfoContextResult cityInfoContext,
                               boolean newsIntent) {
    }
}
