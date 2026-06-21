package cat.complai.services.worker;

import cat.complai.config.CityFeatureFlagService;
import cat.complai.config.TelegramConfiguration;
import cat.complai.dto.sqs.AskSqsMessage;
import cat.complai.services.openrouter.IAskService;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.function.aws.MicronautRequestHandler;

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
 * <p>Beans are resolved lazily from {@link #getApplicationContext()} rather than via
 * {@code @Inject} fields. Field-level injection uses {@code Class.getDeclaredField()}
 * internally, which requires explicit GraalVM reflection registration and is fragile in
 * native images. Looking beans up directly from the context is the safe equivalent.
 */
public class AskWorkerHandler extends MicronautRequestHandler<SQSEvent, SQSBatchResponse> {

    private static final Logger logger = Logger.getLogger(AskWorkerHandler.class.getName());

    // Lazily initialised from getApplicationContext() — see ensureInitialized().
    private IAskService openRouterService;
    private TelegramConfiguration telegramConfig;
    private ObjectMapper mapper;
    private CityFeatureFlagService featureFlagService;
    private boolean initialized = false;

    private void ensureInitialized() {
        if (initialized) return;
        openRouterService = getApplicationContext().getBean(IAskService.class);
        telegramConfig = getApplicationContext().getBean(TelegramConfiguration.class);
        mapper = getApplicationContext().getBean(ObjectMapper.class);
        featureFlagService = getApplicationContext().getBean(CityFeatureFlagService.class);
        initialized = true;
    }

    @Override
    public SQSBatchResponse execute(SQSEvent event) {
        ensureInitialized();

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

                // Skip processing if the city is disabled via the ENABLE_CITY_<cityId> feature flag.
                if (!featureFlagService.isCityEnabled(message.cityId())) {
                    logger.info(() -> "AskWorkerHandler — skipping disabled city messageId=" + messageId
                            + " cityId=" + message.cityId());
                    continue;
                }

                logger.info(() -> "AskWorkerHandler — processing record messageId=" + messageId
                        + " chatId=" + message.chatId() + " cityId=" + message.cityId());

                String token = telegramConfig.getToken(message.cityId());
                AskProcessor processor = new AskProcessor(openRouterService, token, mapper);
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
