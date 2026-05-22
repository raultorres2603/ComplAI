package cat.complai;

import cat.complai.services.ses.SesScheduledReportHandler;
import cat.complai.services.worker.AskWorkerHandler;
import cat.complai.services.worker.FeedbackWorkerHandler;
import cat.complai.services.worker.RedactWorkerHandler;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.function.aws.runtime.APIGatewayV2HTTPEventMicronautLambdaRuntime;
import io.micronaut.function.aws.runtime.AbstractMicronautLambdaRuntime;

import java.util.Map;
import java.util.function.Supplier;

/**
 * ComplAI Lambda entry point — dispatches to the correct runtime event loop
 * based on the {@code AWS_LAMBDA_FUNCTION_NAME} environment variable.
 *
 * <p>The same native binary is shared across five Lambda functions.
 * Without {@link AbstractMicronautLambdaRuntime} the process would start,
 * log "No embedded container found", and exit immediately.</p>
 */
@Introspected(classes = {
    SQSBatchResponse.class, SQSBatchResponse.BatchItemFailure.class,
    SQSEvent.class, SQSEvent.SQSMessage.class
})
public class App {

    public static void main(String[] args) throws Exception {
        String fn = System.getenv("AWS_LAMBDA_FUNCTION_NAME");

        AbstractMicronautLambdaRuntime<?, ?, ?, ?> runtime;
        if (fn == null) {
            runtime = new APIGatewayV2HTTPEventMicronautLambdaRuntime();
        } else if (fn.contains("Redactor")) {
            runtime = new SqsWorkerRuntime(RedactWorkerHandler::new);
        } else if (fn.contains("FeedbackWorker")) {
            runtime = new SqsWorkerRuntime(FeedbackWorkerHandler::new);
        } else if (fn.contains("AskWorker")) {
            runtime = new SqsWorkerRuntime(AskWorkerHandler::new);
        } else if (fn.contains("ScheduledReport")) {
            runtime = new ScheduledEventRuntime();
        } else {
            runtime = new APIGatewayV2HTTPEventMicronautLambdaRuntime();
        }
        runtime.run(args);
    }

    // -----------------------------------------------------------------------
    // SQS Worker Runtime (reusable for all three SQS-triggered functions)
    // -----------------------------------------------------------------------

    /**
     * Generic runtime for SQS-triggered workers. Accepts a handler factory
     * so the same class serves {@link RedactWorkerHandler},
     * {@link FeedbackWorkerHandler}, and {@link AskWorkerHandler}.
     */
    private static final class SqsWorkerRuntime
            extends AbstractMicronautLambdaRuntime<SQSEvent, SQSBatchResponse, SQSEvent, SQSBatchResponse> {

        private final Supplier<RequestHandler<SQSEvent, SQSBatchResponse>> handlerFactory;

        SqsWorkerRuntime(Supplier<RequestHandler<SQSEvent, SQSBatchResponse>> handlerFactory) {
            this.handlerFactory = handlerFactory;
        }

        @Override
        protected RequestHandler<SQSEvent, SQSBatchResponse> createRequestHandler(String... args) {
            return handlerFactory.get();
        }
    }

    // -----------------------------------------------------------------------
    // Scheduled Report Runtime (EventBridge-triggered)
    // -----------------------------------------------------------------------

    /**
     * Runtime for the EventBridge-scheduled statistics report Lambda.
     * The type parameters handle the generic {@code ScheduledEvent} structure
     * as a {@code Map<String, Object>}.
     */
    private static final class ScheduledEventRuntime
            extends AbstractMicronautLambdaRuntime<Map<String, Object>, String, Map<String, Object>, String> {

        @Override
        protected RequestHandler<Map<String, Object>, String> createRequestHandler(String... args) {
            return new SesScheduledReportHandler();
        }
    }
}
