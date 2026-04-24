package cat.complai.utilities.sqs;

import cat.complai.dto.sqs.RedactSqsMessage;
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
 * Publishes complaint letter generation requests to the SQS redact queue.
 *
 * <p>The API Lambda calls {@link #publish(RedactSqsMessage)} and returns {@code 202 Accepted}
 * immediately. The worker Lambda consumes messages from the same queue asynchronously.
 *
 * <p>Any exception from the AWS SDK is wrapped and rethrown so the controller can
 * surface it as {@code 500 INTERNAL} — the user then knows the request was not queued.
 */
@Singleton
public class SqsComplaintPublisher {

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final ObjectMapper mapper;

    private final Logger logger = Logger.getLogger(SqsComplaintPublisher.class.getName());

    @Inject
    public SqsComplaintPublisher(
            @Value("${REDACT_QUEUE_URL:}") String queueUrl,
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
     * {@code HttpWrapper}: tests create anonymous subclasses to control behaviour.
     */
    protected SqsComplaintPublisher() {
        this.queueUrl  = null;
        this.sqsClient = null;
        this.mapper    = new ObjectMapper();
    }

    /**
     * Serialises {@code message} to JSON and enqueues it on the redact queue.
     *
     * @throws RuntimeException if serialisation fails or the SQS call is unsuccessful
     */
    public void publish(RedactSqsMessage message) {
        if (queueUrl == null || queueUrl.isBlank()) {
            throw new IllegalStateException("REDACT_QUEUE_URL is not configured — cannot publish complaint message");
        }
        try {
            String body = mapper.writeValueAsString(message);
            logger.info(() -> "SQS publish starting — queueUrl=" + queueUrl
                    + " s3Key=" + message.s3Key() + " conversationId=" + message.conversationId());
            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());
            logger.info(() -> "SQS publish completed — queueUrl=" + queueUrl
                    + " s3Key=" + message.s3Key() + " conversationId=" + message.conversationId());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "SQS publish failed — queueUrl=" + queueUrl
                    + " s3Key=" + message.s3Key() + " conversationId=" + message.conversationId()
                    + " error=" + e.getMessage(), e);
            throw new RuntimeException("SQS publish failed: " + e.getMessage(), e);
        }
    }
}

