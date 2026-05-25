package cat.complai.services.worker;

import cat.complai.utilities.http.HttpWrapper;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.utilities.s3.S3PdfUploader;
import cat.complai.dto.sqs.RedactSqsMessage;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.function.aws.MicronautRequestHandler;

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
 * <p>Beans are resolved lazily from {@link #getApplicationContext()} rather than via
 * {@code @Inject} fields. Field-level injection uses {@code Class.getDeclaredField()}
 * internally, which requires explicit GraalVM reflection registration and is fragile in
 * native images. Looking beans up directly from the context is the safe equivalent.
 */
public class RedactWorkerHandler extends MicronautRequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger logger = Logger.getLogger(RedactWorkerHandler.class.getName());

    private static final String TIMEOUT_PROP = "OPENROUTER_OVERALL_TIMEOUT_SECONDS";

    // Lazily initialised from getApplicationContext() — see ensureInitialized().
    private RedactPromptBuilder promptBuilder;
    private HttpWrapper httpWrapper;
    private S3PdfUploader s3PdfUploader;
    private ObjectMapper mapper;
    private int overallTimeoutSeconds;
    private boolean initialized = false;

    private void ensureInitialized() {
        if (initialized) return;
        promptBuilder = getApplicationContext().getBean(RedactPromptBuilder.class);
        httpWrapper = getApplicationContext().getBean(HttpWrapper.class);
        s3PdfUploader = getApplicationContext().getBean(S3PdfUploader.class);
        mapper = getApplicationContext().getBean(ObjectMapper.class);
        overallTimeoutSeconds = Integer.parseInt(
                getApplicationContext().getEnvironment()
                        .getProperty(TIMEOUT_PROP, String.class)
                        .orElse("60"));
        initialized = true;
    }

    @Override
    public SQSBatchResponse execute(SQSEvent event) {
        ensureInitialized();

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
