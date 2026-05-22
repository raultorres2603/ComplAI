package cat.complai;

import cat.complai.services.ses.SesScheduledReportHandler;
import cat.complai.services.worker.AskWorkerHandler;
import cat.complai.services.worker.FeedbackWorkerHandler;
import cat.complai.services.worker.RedactWorkerHandler;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import io.micronaut.function.aws.runtime.APIGatewayV2HTTPEventMicronautLambdaRuntime;
import io.micronaut.function.aws.runtime.AbstractMicronautLambdaRuntime;

import java.util.Map;

/**
 * ComplAI Lambda Application Entry Point (Native Image Custom Runtime).
 *
 * <p>This class replaces the standard {@code Micronaut.run()} launcher with
 * {@link AbstractMicronautLambdaRuntime} subclasses that enter the AWS Lambda
 * Runtime API event loop. Without this, the native binary would start up, log
 * "No embedded container found. Running as CLI application", and then exit
 * immediately with a {@code Runtime.ExitError}.
 *
 * <p>The native image is shared across five Lambda functions (one HTTP API
 * plus four workers). The {@code AWS_LAMBDA_FUNCTION_NAME} environment variable
 * is used at startup to select the correct runtime event loop and handler:
 *
 * <ul>
 *   <li><b>ComplAILambda-*</b> (API Gateway HTTP API v2) →
 *       {@link APIGatewayV2HTTPEventMicronautLambdaRuntime}</li>
 *   <li><b>ComplAIRedactorLambda-*</b> (SQS) →
 *       {@link SqsWorkerRuntime} → {@link RedactWorkerHandler}</li>
 *   <li><b>ComplAIFeedbackWorkerLambda-*</b> (SQS) →
 *       {@link SqsWorkerRuntime} → {@link FeedbackWorkerHandler}</li>
 *   <li><b>ComplAIAskWorkerLambda-*</b> (SQS) →
 *       {@link SqsWorkerRuntime} → {@link AskWorkerHandler}</li>
 *   <li><b>ComplAIScheduledReportLambda-*</b> (EventBridge) →
 *       {@link ScheduledReportRuntime} → {@link SesScheduledReportHandler}</li>
 * </ul>
 *
 * <p>Each handler creates its own {@code ApplicationContext} through the
 * {@code MicronautLambdaHandler} base class constructor, which also processes
 * {@code @Inject} annotations. All handler dependencies ({@code HttpWrapper},
 * {@code S3PdfUploader}, etc.) are annotated {@code @Singleton} and are
 * discoverable by the annotation processor.
 */
public class App {

    public static void main(String[] args) throws Exception {
        // AWS_LAMBDA_FUNCTION_NAME is set by the Lambda runtime. Each of our
        // five CDK-deployed functions has its own name pattern. We match on
        // substrings to select the correct runtime loop and handler.
        String functionName = System.getenv("AWS_LAMBDA_FUNCTION_NAME");

        if (functionName != null) {
            // SQS-triggered workers
            if (functionName.contains("Redactor")) {
                new RedactWorkerRuntime().run(args);
                return;
            }
            if (functionName.contains("FeedbackWorker")) {
                new FeedbackWorkerRuntime().run(args);
                return;
            }
            if (functionName.contains("AskWorker")) {
                new AskWorkerRuntime().run(args);
                return;
            }
            // EventBridge-scheduled report
            if (functionName.contains("ScheduledReport")) {
                new ScheduledReportRuntime().run(args);
                return;
            }
        }

        // Default (and most common): API Gateway HTTP API v2 triggered Lambda.
        new APIGatewayV2HTTPEventMicronautLambdaRuntime().run(args);
    }

    // -----------------------------------------------------------------------
    // SQS Worker Runtimes
    // -----------------------------------------------------------------------

    /**
     * Base runtime for SQS-triggered worker Lambdas. The type parameters
     * {@code <SQSEvent, SQSBatchResponse, SQSEvent, SQSBatchResponse>}
     * ensure the Lambda Runtime API event JSON is deserialised to
     * {@link SQSEvent} and the handler response to {@link SQSBatchResponse}.
     */
    private abstract static class BaseSqsWorkerRuntime
            extends AbstractMicronautLambdaRuntime<SQSEvent, SQSBatchResponse, SQSEvent, SQSBatchResponse> {
        // Concrete subclasses override createRequestHandler to return the
        // specific MicronautRequestHandler for each worker function.
    }

    private static final class RedactWorkerRuntime extends BaseSqsWorkerRuntime {
        @Override
        protected RequestHandler<SQSEvent, SQSBatchResponse> createRequestHandler(String... args) {
            // The handler constructor (inherited from MicronautLambdaHandler)
            // creates the ApplicationContext and wires @Inject fields.
            return new RedactWorkerHandler();
        }
    }

    private static final class FeedbackWorkerRuntime extends BaseSqsWorkerRuntime {
        @Override
        protected RequestHandler<SQSEvent, SQSBatchResponse> createRequestHandler(String... args) {
            return new FeedbackWorkerHandler();
        }
    }

    private static final class AskWorkerRuntime extends BaseSqsWorkerRuntime {
        @Override
        protected RequestHandler<SQSEvent, SQSBatchResponse> createRequestHandler(String... args) {
            return new AskWorkerHandler();
        }
    }

    // -----------------------------------------------------------------------
    // Scheduled Report Runtime
    // -----------------------------------------------------------------------

    /**
     * Runtime for the EventBridge-scheduled SES report Lambda. The type
     * parameters {@code <Map<String, Object>, String, Map<String, Object>, String>}
     * handle the generic EventBridge ScheduledEvent structure.
     */
    private static final class ScheduledReportRuntime
            extends AbstractMicronautLambdaRuntime<Map<String, Object>, String, Map<String, Object>, String> {
        @Override
        protected RequestHandler<Map<String, Object>, String> createRequestHandler(String... args) {
            return new SesScheduledReportHandler();
        }
    }
}
