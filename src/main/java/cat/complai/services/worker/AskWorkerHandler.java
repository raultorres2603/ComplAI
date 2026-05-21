package cat.complai.services.worker;

import cat.complai.config.TelegramConfiguration;
import cat.complai.dto.sqs.AskSqsMessage;
import cat.complai.services.openrouter.IOpenRouterService;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.function.aws.MicronautRequestHandler;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQS-triggered Lambda handler for async Telegram ask responses.
 *
 * <p>Delegates all business logic to {@link AskProcessor} so the handler only
 * concerns itself with DI wiring and SQS batch-item-failure reporting. This separation also
 * makes the core logic unit-testable without starting a Micronaut application context.
 *
 * <p>Failures are reported as batch item failures so SQS retries only the failed records
 * (up to {@code maxReceiveCount} times before routing to the DLQ).
 */
public class AskWorkerHandler extends MicronautRequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger logger = Logger.getLogger(AskWorkerHandler.class.getName());

    @Inject
    private IOpenRouterService openRouterService;

    @Inject
    private TelegramConfiguration telegramConfig;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public SQSBatchResponse execute(SQSEvent event) {
        int recordCount = event.getRecords() != null ? event.getRecords().size() : 0;
        logger.info(() -> "AskWorkerHandler — received SQS batch recordCount=" + recordCount);

        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        if (event.getRecords() == null || event.getRecords().isEmpty()) {
            logger.info("AskWorkerHandler — empty or null batch, returning empty response");
            return SQSBatchResponse.builder()
                    .withBatchItemFailures(failures)
                    .build();
        }

        for (SQSEvent.SQSMessage record : event.getRecords()) {
            String messageId = record.getMessageId();
            try {
                AskSqsMessage message = mapper.readValue(record.getBody(), AskSqsMessage.class);
                logger.info(() -> "AskWorkerHandler — processing record messageId=" + messageId
                        + " chatId=" + message.chatId() + " cityId=" + message.cityId());

                String token = telegramConfig.getToken(message.cityId());
                AskProcessor processor = new AskProcessor(openRouterService, token);
                processor.process(message);

                logger.info(() -> "AskWorkerHandler — record completed successfully messageId=" + messageId
                        + " chatId=" + message.chatId());
            } catch (Exception e) {
                logger.log(Level.SEVERE, "AskWorkerHandler — record failed messageId=" + messageId, e);
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(messageId)
                        .build());
            }
        }

        logger.info(() -> "AskWorkerHandler — batch complete recordCount=" + recordCount
                + " successCount=" + (recordCount - failures.size()) + " failureCount=" + failures.size());

        return SQSBatchResponse.builder()
                .withBatchItemFailures(failures)
                .build();
    }
}
