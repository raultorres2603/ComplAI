package cat.complai.services.openrouter;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Extracted shared logic for building RAG (Retrieval-Augmented Generation)
 * contexts. Previously duplicated verbatim in {@link OpenRouterServices} and
 * {@link StreamingOrchestrator}, this class centralises context-detection
 * dispatching, error-safe lookup, and logging.
 */
@Singleton
public class RagContextHelper {

    private final RagContextBuilder ragContextBuilder;
    private final IntentDetector intentDetector;
    private final Logger logger = Logger.getLogger(RagContextHelper.class.getName());

    @Inject
    public RagContextHelper(RagContextBuilder ragContextBuilder, IntentDetector intentDetector) {
        this.ragContextBuilder = ragContextBuilder;
        this.intentDetector = intentDetector;
    }

    // -----------------------------------------------------------------------
    // Public record — aggregates all context results in one object
    // -----------------------------------------------------------------------

    public record RagContexts(ProcedureContextResult procedureContext,
                               EventContextResult eventContext,
                               NewsContextResult newsContext,
                               CityInfoContextResult cityInfoContext,
                               boolean newsIntent) {
    }

    // -----------------------------------------------------------------------
    // Main entry point — dispatches to the appropriate context builders
    // -----------------------------------------------------------------------

    /**
     * Detects which context types the question requires and builds them
     * (synchronously or asynchronously depending on the combination).
     *
     * @param question       the user's question text
     * @param cityId         city identifier for RAG data selection
     * @param conversationId optional conversation ID for logging
     * @param operationName  caller label for log messages (e.g. "ask()" or "streamAsk()")
     * @param executor       executor for parallel async lookups
     * @return the combined context results
     */
    public RagContexts buildRagContexts(String question, String cityId, String conversationId,
                                         String operationName, ExecutorService executor) {
        long detectionStart = System.nanoTime();
        ContextRequirements requirements = intentDetector.detectContextRequirements(question, cityId);
        long detectionDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - detectionStart);

        logger.fine(() -> String.format(
                "%s context detection — conversationId=%s procedure=%s event=%s news=%s cityInfo=%s durationMs=%d",
                operationName, conversationId,
                requirements.needsProcedureContext(),
                requirements.needsEventContext(),
                requirements.needsNewsContext(),
                requirements.needsCityInfoContext(),
                detectionDurationMs));

        if (!requirements.needsProcedureContext() && !requirements.needsEventContext()
                && !requirements.needsNewsContext() && !requirements.needsCityInfoContext()) {
            return new RagContexts(null, null, null, null, false);
        }

        if (requirements.needsNewsContext()) {
            NewsContextResult newsContext = safelyBuildNewsContext(question, cityId, conversationId, operationName);
            logger.fine(() -> String.format("%s news retrieval completed — conversationId=%s city=%s hitCount=%d",
                    operationName, conversationId, cityId,
                    newsContext != null && newsContext.sources() != null
                            ? newsContext.sources().size()
                            : 0));
            return new RagContexts(null, null, newsContext, null, true);
        }

        if (requirements.needsProcedureContext() && requirements.needsEventContext()) {
            long ragStart = System.nanoTime();
            CompletableFuture<ProcedureContextResult> procedureFuture = ragContextBuilder
                    .buildProcedureContextResultAsync(question, cityId, executor)
                    .exceptionally(ex -> {
                        logRagFailure(operationName, conversationId, cityId, "procedure", ex);
                        return null;
                    });
            CompletableFuture<EventContextResult> eventFuture = ragContextBuilder
                    .buildEventContextResultAsync(question, cityId, executor)
                    .exceptionally(ex -> {
                        logRagFailure(operationName, conversationId, cityId, "event", ex);
                        return null;
                    });

            ProcedureContextResult procedureContext = procedureFuture.join();
            EventContextResult eventContext = eventFuture.join();

            long ragDurationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ragStart);
            logger.fine(() -> String.format("%s RAG build completed — conversationId=%s mode=bounded-parallel durationMs=%d",
                    operationName, conversationId, ragDurationMs));
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

    // -----------------------------------------------------------------------
    // Error-safe context build helpers
    // -----------------------------------------------------------------------

    @Nullable
    public NewsContextResult safelyBuildNewsContext(String question, String cityId,
                                                     String conversationId, String operationName) {
        long start = System.nanoTime();
        try {
            NewsContextResult result = ragContextBuilder.buildNewsContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            logger.fine(() -> String.format("%s RAG build completed — conversationId=%s mode=news-only durationMs=%d",
                    operationName, conversationId, durationMs));
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "news", e);
            return null;
        }
    }

    @Nullable
    public ProcedureContextResult safelyBuildProcedureContext(String question, String cityId,
                                                              String conversationId, String operationName) {
        long start = System.nanoTime();
        try {
            ProcedureContextResult result = ragContextBuilder.buildProcedureContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            logger.fine(() -> String.format("%s RAG build completed — conversationId=%s mode=procedure-only durationMs=%d",
                    operationName, conversationId, durationMs));
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "procedure", e);
            return null;
        }
    }

    @Nullable
    public EventContextResult safelyBuildEventContext(String question, String cityId,
                                                      String conversationId, String operationName) {
        long start = System.nanoTime();
        try {
            EventContextResult result = ragContextBuilder.buildEventContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            logger.fine(() -> String.format("%s RAG build completed — conversationId=%s mode=event-only durationMs=%d",
                    operationName, conversationId, durationMs));
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "event", e);
            return null;
        }
    }

    @Nullable
    public CityInfoContextResult safelyBuildCityInfoContext(String question, String cityId,
                                                            String conversationId, String operationName) {
        long start = System.nanoTime();
        try {
            CityInfoContextResult result = ragContextBuilder.buildCityInfoContextResult(question, cityId);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            logger.fine(() -> String.format("%s RAG build completed — conversationId=%s mode=cityinfo-only durationMs=%d",
                    operationName, conversationId, durationMs));
            return result;
        } catch (Exception e) {
            logRagFailure(operationName, conversationId, cityId, "cityinfo", e);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Failure logging
    // -----------------------------------------------------------------------

    private void logRagFailure(String operationName, String conversationId, String cityId,
                                String contextType, Throwable failure) {
        Throwable rootCause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        logger.warning(() -> String.format("%s RAG build failed — conversationId=%s city=%s contextType=%s error=%s",
                operationName, conversationId, cityId, contextType, rootCause.getMessage()));
    }
}
