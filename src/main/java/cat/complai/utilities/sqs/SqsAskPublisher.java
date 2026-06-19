package cat.complai.utilities.sqs;

import cat.complai.dto.sqs.AskSqsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publishes Telegram ask requests to the SQS ask queue.
 *
 * <p>The API Lambda calls {@link #publish(AskSqsMessage)} after sending a "processing"
 * message to the user, then returns {@code 200 OK} to Telegram immediately. The worker
 * Lambda consumes messages from the same queue asynchronously, calls the AI, and sends
 * the answer back to the user via the Telegram Bot API.
 *
 * <p>Any exception from the AWS SDK is wrapped and rethrown so the caller can
 * surface an appropriate error to the user.
 */
@Singleton
public class SqsAskPublisher implements ISqsAskPublisher {

    /** Maximum number of visible messages on the queue before we reject new publishes. */
    static final int MAX_QUEUE_DEPTH = Integer.parseInt(
            System.getenv().getOrDefault("SQS_MAX_QUEUE_DEPTH", "1000"));

    // Cache queue depth checks for 1 second to avoid an SQS API call on every publish.
    private static final Cache<String, Integer> QUEUE_DEPTH_CACHE = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.SECONDS)
            .maximumSize(10)
            .build();

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final ObjectMapper mapper;

    private final Logger logger = Logger.getLogger(SqsAskPublisher.class.getName());

    @Inject
    public SqsAskPublisher(
            @Value("${ASK_QUEUE_URL:}") String queueUrl,
            SqsClient sqsClient,
            ObjectMapper mapper) {
        this.queueUrl = queueUrl;
        this.mapper   = mapper;
        this.sqsClient = sqsClient;
    }

    /**
     * Protected no-arg constructor for test subclassing. Follows the same pattern as
     * {@code SqsComplaintPublisher}: tests create anonymous subclasses to control behaviour.
     */
    protected SqsAskPublisher() {
        this.queueUrl  = null;
        this.sqsClient = null;
        this.mapper    = new ObjectMapper();
    }

    /**
     * Serialises {@code message} to JSON and enqueues it on the ask queue.
     *
     * @throws RuntimeException if the queue depth is exceeded, serialisation fails,
     *                          or the SQS call is unsuccessful
     */
    @Override
    public void publish(AskSqsMessage message) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalStateException("ASK_QUEUE_URL is not configured — cannot publish ask message");
        }
        if (isQueueDepthExceeded()) {
            throw new RuntimeException("Queue depth exceeds maximum of " + MAX_QUEUE_DEPTH
                    + " — message rejected to prevent backlog");
        }
        try {
            String body = mapper.writeValueAsString(message);
            logger.info(() -> "SQS ask publish starting — queueUrl=" + queueUrl
                    + " chatId=" + message.chatId() + " conversationId=" + message.conversationId());
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
            logger.info(() -> "SQS ask publish completed — queueUrl=" + queueUrl
                    + " chatId=" + message.chatId() + " conversationId=" + message.conversationId());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "SQS ask publish failed — queueUrl=" + queueUrl
                    + " chatId=" + message.chatId() + " conversationId=" + message.conversationId()
                    + " error=" + e.getMessage(), e);
            throw new RuntimeException("SQS ask publish failed: " + e.getMessage(), e);
        }
    }

    /**
     * Checks whether the SQS queue depth has exceeded the maximum allowed threshold.
     *
     * <p>Uses a cached {@link GetQueueAttributesRequest} call (1-second TTL) to avoid
     * an extra API call on every publish. If the check itself fails (e.g. network error)
     * the method returns {@code false} so the message can proceed rather than blocking
     * all traffic due to a monitoring failure.
     *
     * @return {@code true} if the queue is too deep and the message should be rejected
     */
    protected boolean isQueueDepthExceeded() {
        if (sqsClient == null || queueUrl == null || queueUrl.isBlank()) {
            return false;
        }
        try {
            Integer depth = QUEUE_DEPTH_CACHE.get(queueUrl, url -> {
                var resp = sqsClient.getQueueAttributes(
                        GetQueueAttributesRequest.builder()
                                .queueUrl(url)
                                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                                .build());
                String val = resp.attributes().get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
                return val != null ? Integer.parseInt(val) : 0;
            });
            if (depth != null && depth > MAX_QUEUE_DEPTH) {
                logger.warning(() -> "Queue depth " + depth + " exceeds limit of " + MAX_QUEUE_DEPTH
                        + " for queueUrl=" + queueUrl);
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to check queue depth for " + queueUrl
                    + " — proceeding with publish", e);
            return false;
        }
    }
}
