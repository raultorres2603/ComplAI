package cat.complai.services.openrouter;

import cat.complai.dto.openrouter.AskStreamResult;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.OutputFormat;
import cat.complai.dto.openrouter.Source;
import cat.complai.helpers.openrouter.LanguageDetector;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.helpers.openrouter.TokenEstimator;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final RagContextHelper ragContextHelper;
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
                              RagContextHelper ragContextHelper,
                              ClarificationService clarificationService,
                              StreamingOrchestrator streamingOrchestrator,
                              RedactOrchestrator redactOrchestrator,
                              RedactPromptBuilder promptBuilder) {
        this.validationService = validationService;
        this.conversationService = conversationService;
        this.aiResponseService = aiResponseService;
        this.intentDetector = intentDetector;
        this.ragContextBuilder = ragContextBuilder;
        this.ragContextHelper = ragContextHelper;
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
        return ask(question, conversationId, cityId, null);
    }

    @Override
    public OpenRouterResponseDto ask(String question, String conversationId, String cityId, String preferredLang) {
        int inputLength = question != null ? question.length() : 0;
        log.info("ask() called — conversationId={} inputLength={} city={} preferredLang={}",
                conversationId, inputLength, cityId, preferredLang);

        var validationError = validationService.validateQuestion(question);
        if (validationError.isPresent()) {
            log.debug("ask() rejected — reason={} conversationId={}", validationError.get().getError(), conversationId);
            return validationError.get();
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        // Use preferred language if provided, otherwise detect from question text
        String detectedLanguage;
        if (preferredLang != null && !preferredLang.isBlank()) {
            detectedLanguage = RedactPromptBuilder.normalizeLanguageCode(preferredLang);
            log.debug("ask() using preferred language={} conversationId={}", detectedLanguage, conversationId);
        } else {
            detectedLanguage = RedactPromptBuilder.normalizeLanguageCode(
                    LanguageDetector.detect(question));
            log.debug("ask() detected language={} conversationId={}", detectedLanguage, conversationId);
        }

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
                    if (resolvedCtx != null && resolvedCtx.contextBlock() != null) {
                        resolvedMessages.add(Map.of("role", "system", "content", resolvedCtx.contextBlock()));
                    }
                    var resolvedHistory = conversationService.getConversationHistory(conversationId);
                    conversationService.addToMessages(resolvedMessages, resolvedHistory);
                    resolvedMessages.add(Map.of("role", "user",
                            "content", question != null ? question.trim() : ""));

                    long resolvedProcHash = computeSourcesHash(
                            resolvedCtx != null ? resolvedCtx.sources() : List.of());
                    OpenRouterResponseDto resolvedResponse =
                            aiResponseService.callOpenRouterAndExtract(
                                    resolvedMessages, cityId, resolvedProcHash, 0L);

                    List<Source> resolvedSources = new ArrayList<>();
                    ragContextBuilder.validateAndAddSources(resolvedSources,
                            resolvedCtx != null ? resolvedCtx.sources() : null, "procedure");
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

        RagContextHelper.RagContexts ragContexts = ragContextHelper.buildRagContexts(question, cityId, conversationId, "ask()", ragExecutor);
        ProcedureContextResult procCtx = ragContexts.procedureContext();
        EventContextResult eventCtx = ragContexts.eventContext();
        NewsContextResult newsCtx = ragContexts.newsContext();
        CityInfoContextResult cityInfoCtx = ragContexts.cityInfoContext();

        if (ragContexts.newsIntent() && (newsCtx == null || newsCtx.sources().isEmpty())) {
            String fallbackMessage = buildNoNewsFoundMessage(detectedLanguage, cityId);
            if (conversationId != null && !conversationId.isBlank()) {
                conversationService.updateConversationHistory(conversationId, question, fallbackMessage);
            }
            log.info("ask() news fallback used - conversationId={} city={}", conversationId, cityId);
            return new OpenRouterResponseDto(true, fallbackMessage, null, 200, OpenRouterErrorCode.NONE, null,
                    List.of());
        }

        if (procCtx != null && procCtx.contextBlock() != null) {
            messages.add(Map.of("role", "system", "content", procCtx.contextBlock()));
        }
        if (eventCtx != null && eventCtx.contextBlock() != null) {
            messages.add(Map.of("role", "system", "content", eventCtx.contextBlock()));
        }
        if (newsCtx != null && newsCtx.contextBlock() != null) {
            messages.add(Map.of("role", "system", "content", newsCtx.contextBlock()));
        }
        if (cityInfoCtx != null && cityInfoCtx.contextBlock() != null) {
            messages.add(Map.of("role", "system", "content", cityInfoCtx.contextBlock()));
        }

        var history = conversationService.getConversationHistory(conversationId);
        conversationService.addToMessages(messages, history);

        if (question != null) {
            messages.add(Map.of("role", "user", "content", question.trim()));
        } else {
            messages.add(Map.of("role", "user", "content", ""));
        }

        // Calculate and log context metrics
        int systemTokens = 0;
        int historyTokens = 0;
        for (Map<String, Object> msg : messages) {
            if (!(msg.get("content") instanceof String content))
                continue;
            String role = msg.get("role") instanceof String s ? s : "";
            int tokens = TokenEstimator.estimateTokenCount(content);

            if ("system".equals(role)) {
                systemTokens += tokens;
            } else if (!"user".equals(role)) {
                historyTokens += tokens;
            }
        }
        int userTokens = TokenEstimator.estimateTokenCount(question);
        final int totalTokens = systemTokens + historyTokens + userTokens;
        final int historyTurns = history != null ? (history.size() / 2) : 0;

        log.debug(
                "ask() CONTEXT METRICS — systemTokens={} historyTokens={} userTokens={} totalTokens={} historyTurns={} conversationId={}",
                systemTokens, historyTokens, userTokens, totalTokens, historyTurns, conversationId);
        log.debug("ask() messages prepared — messageCount={} conversationId={}", messages.size(), conversationId);

        // Calculate context hashes for caching
        long procContextHash = computeSourcesHash(procCtx != null ? procCtx.sources() : List.of());
        List<Source> eventAndNewsSourcesForHash = new ArrayList<>();
        if (eventCtx != null && eventCtx.sources() != null) {
            eventAndNewsSourcesForHash.addAll(eventCtx.sources());
        }
        if (newsCtx != null && newsCtx.sources() != null) {
            eventAndNewsSourcesForHash.addAll(newsCtx.sources());
        }
        if (cityInfoCtx != null && cityInfoCtx.sources() != null) {
            eventAndNewsSourcesForHash.addAll(cityInfoCtx.sources());
        }
        long eventContextHash = computeSourcesHash(eventAndNewsSourcesForHash);

        OpenRouterResponseDto response = aiResponseService.callOpenRouterAndExtract(
                messages, cityId, procContextHash, eventContextHash);

        // Collect all sources
        List<Source> mergedSources = new ArrayList<>();
        ragContextBuilder.validateAndAddSources(mergedSources,
                procCtx != null ? procCtx.sources() : null, "procedure");
        ragContextBuilder.validateAndAddSources(mergedSources,
                eventCtx != null ? eventCtx.sources() : null, "event");
        ragContextBuilder.validateAndAddSources(mergedSources,
                newsCtx != null ? newsCtx.sources() : null, "news");
        ragContextBuilder.validateAndAddSources(mergedSources,
                cityInfoCtx != null ? cityInfoCtx.sources() : null, "city_info");

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
    // Private helpers — utilities
    // -----------------------------------------------------------------------

    private ExecutorService createRagExecutor() {
        return Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "openrouter-rag");
            thread.setDaemon(true);
            return thread;
        });
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
        return RedactPromptBuilder.buildNoNewsFoundMessage(detectedLanguage, cityId);
    }

    private String buildEventDateWindowClarificationMessage(String detectedLanguage) {
        return RedactPromptBuilder.buildEventDateWindowClarificationMessage(detectedLanguage);
    }
}
