package cat.complai.worker;

import cat.complai.http.HttpWrapper;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.s3.S3PdfUploader;
import cat.complai.sqs.dto.RedactSqsMessage;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import io.micronaut.function.aws.MicronautRequestHandler;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQS-triggered Lambda handler for async complaint letter generation.
 *
 * <p>Delegates all business logic to {@link ComplaintLetterGenerator} so the handler only
 * concerns itself with DI wiring and SQS batch-item-failure reporting. This separation also
 * makes the core logic unit-testable without starting a Micronaut application context.
 *
 * <p>Failures are reported as batch item failures so SQS retries only the failed records
 * (up to {@code maxReceiveCount} times before routing to the DLQ).
 */
public class RedactWorkerHandler extends MicronautRequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger logger = Logger.getLogger(RedactWorkerHandler.class.getName());

    @Inject
    private RedactPromptBuilder promptBuilder;

    @Inject
    private HttpWrapper httpWrapper;

    @Inject
    private S3PdfUploader s3PdfUploader;

    @Value("${OPENROUTER_OVERALL_TIMEOUT_SECONDS:60}")
    private int overallTimeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public SQSBatchResponse execute(SQSEvent event) {
        int recordCount = event.getRecords() != null ? event.getRecords().size() : 0;
        logger.info(() -> "RedactWorkerHandler — received SQS batch recordCount=" + recordCount);

        ComplaintLetterGenerator generator = new ComplaintLetterGenerator(
                promptBuilder, httpWrapper, s3PdfUploader, overallTimeoutSeconds);

        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        if (event.getRecords() == null || event.getRecords().isEmpty()) {
            logger.info("RedactWorkerHandler — empty or null batch, returning empty response");
            return SQSBatchResponse.builder()
                    .withBatchItemFailures(failures)
                    .build();
        }

        for (SQSEvent.SQSMessage record : event.getRecords()) {
            String messageId = record.getMessageId();
            try {
                RedactSqsMessage message = mapper.readValue(record.getBody(), RedactSqsMessage.class);
                logger.info(() -> "RedactWorkerHandler — processing record messageId=" + messageId
                        + " s3Key=" + message.s3Key() + " conversationId=" + message.conversationId());
                generator.generate(message);
                logger.info(() -> "RedactWorkerHandler — record completed successfully messageId=" + messageId
                        + " s3Key=" + message.s3Key());
            } catch (Exception e) {
                logger.log(Level.SEVERE, "RedactWorkerHandler — record failed messageId=" + messageId, e);
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(messageId)
                        .build());
            }
        }

        logger.info(() -> "RedactWorkerHandler — batch complete recordCount=" + recordCount
                + " successCount=" + (recordCount - failures.size()) + " failureCount=" + failures.size());

        return SQSBatchResponse.builder()
                .withBatchItemFailures(failures)
                .build();
    }
}
