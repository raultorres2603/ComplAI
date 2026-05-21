package cat.complai.utilities.sqs;

import cat.complai.dto.sqs.AskSqsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.net.URI;
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
public class SqsAskPublisher {

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final ObjectMapper mapper;

    private final Logger logger = Logger.getLogger(SqsAskPublisher.class.getName());

    @Inject
    public SqsAskPublisher(
            @Value("${ASK_QUEUE_URL:}") String queueUrl,
            @Value("${COMPLAINTS_REGION:eu-west-1}") String region,
            @Value("${AWS_ENDPOINT_URL:}") String endpointUrl) {
        this.queueUrl = queueUrl;
        this.mapper   = new ObjectMapper();
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(region));
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        this.sqsClient = builder.build();
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
     * @throws RuntimeException if serialisation fails or the SQS call is unsuccessful
     */
    public void publish(AskSqsMessage message) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalStateException("ASK_QUEUE_URL is not configured — cannot publish ask message");
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
}
