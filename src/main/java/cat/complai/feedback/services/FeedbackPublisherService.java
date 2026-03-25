package cat.complai.feedback.services;

import cat.complai.feedback.controllers.dto.FeedbackAcceptedDto;
import cat.complai.feedback.controllers.dto.FeedbackRequest;
import cat.complai.feedback.dto.FeedbackErrorCode;
import cat.complai.feedback.dto.FeedbackResult;
import cat.complai.feedback.dto.FeedbackSqsMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Publishes user feedback submissions to the SQS feedback queue.
 *
 * <p>
 * The API Lambda calls {@link #publishFeedback(FeedbackRequest, String)} and
 * returns
 * {@code 202 Accepted} immediately. The worker Lambda consumes messages from
 * the queue
 * asynchronously and uploads JSON to S3.
 *
 * <p>
 * Validation errors return typed {@link FeedbackErrorCode} results rather than
 * throwing exceptions, allowing the controller to respond with appropriate HTTP
 * status codes.
 */
@Singleton
public class FeedbackPublisherService {

    private final SqsClient sqsClient;
    private final String queueUrl;
    private final ObjectMapper mapper;
    private final int maxMessageLength;
    private final int maxUsernameLength;

    private final Logger logger = Logger.getLogger(FeedbackPublisherService.class.getName());

    @Inject
    public FeedbackPublisherService(
            @Value("${feedback.queue-url:}") String queueUrl,
            @Value("${feedback.queue-region:eu-west-1}") String queueRegion,
            @Value("${AWS_ENDPOINT_URL:}") String endpointUrl,
            @Value("${feedback.max-message-length:5000}") int maxMessageLength,
            @Value("${feedback.max-username-length:200}") int maxUsernameLength) {
        this.queueUrl = queueUrl;
        this.mapper = new ObjectMapper();
        this.maxMessageLength = maxMessageLength;
        this.maxUsernameLength = maxUsernameLength;
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(queueRegion));
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            builder.endpointOverride(URI.create(endpointUrl));
        }
        this.sqsClient = builder.build();
    }

    /**
     * Protected no-arg constructor for test subclassing.
     */
    protected FeedbackPublisherService() {
        this.queueUrl = null;
        this.sqsClient = null;
        this.mapper = new ObjectMapper();
        this.maxMessageLength = 5000;
        this.maxUsernameLength = 200;
    }

    /**
     * Validates the feedback request, generates a unique feedbackId, serializes to
     * JSON,
     * and publishes to SQS. Returns a typed result for pattern matching.
     *
     * @param request the feedback request from the client
     * @param city    the city context extracted from the JWT claim
     * @return Success with feedbackId or Error with typed error code
     */
    public FeedbackResult publishFeedback(FeedbackRequest request, String city) {
        // Validate all required fields
        if (request == null || request.userName() == null || request.userName().isBlank()) {
            logger.warning(() -> "Feedback validation failed: userName is missing or blank");
            return new FeedbackResult.Error(FeedbackErrorCode.VALIDATION, "userName is required");
        }
        if (request.idUser() == null || request.idUser().isBlank()) {
            logger.warning(() -> "Feedback validation failed: idUser is missing or blank");
            return new FeedbackResult.Error(FeedbackErrorCode.VALIDATION, "idUser is required");
        }
        if (request.message() == null || request.message().isBlank()) {
            logger.warning(() -> "Feedback validation failed: message is missing or blank");
            return new FeedbackResult.Error(FeedbackErrorCode.VALIDATION, "message is required");
        }

        // Length-cap validations
        if (request.userName().length() > maxUsernameLength) {
            return new FeedbackResult.Error(FeedbackErrorCode.VALIDATION,
                    "userName exceeds maximum length of " + maxUsernameLength + " characters");
        }
        if (request.idUser().length() > 50) {
            return new FeedbackResult.Error(FeedbackErrorCode.VALIDATION,
                    "idUser exceeds maximum length of 50 characters");
        }
        if (request.message().length() > maxMessageLength) {
            return new FeedbackResult.Error(FeedbackErrorCode.VALIDATION,
                    "message exceeds maximum length of " + maxMessageLength + " characters");
        }

        // Generate unique feedbackId and timestamp
        String feedbackId = UUID.randomUUID().toString();
        long timestamp = Instant.now().toEpochMilli();

        logger.info(() -> "Feedback publication starting — feedbackId=" + feedbackId
                + " city=" + city + " userName=" + request.userName());

        try {
            // Build and serialize the SQS message
            FeedbackSqsMessage sqsMessage = new FeedbackSqsMessage(
                    feedbackId,
                    timestamp,
                    city,
                    request.userName(),
                    request.idUser(),
                    request.message());

            String messageBody = mapper.writeValueAsString(sqsMessage);

            // Publish to SQS
            if (queueUrl == null || queueUrl.isBlank()) {
                logger.severe("Feedback publication failed: FEEDBACK_QUEUE_URL is not configured");
                return new FeedbackResult.Error(FeedbackErrorCode.QUEUE_PUBLISH_FAILED,
                        "Queue is not configured");
            }

            sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(messageBody)
                    .build());

            logger.info(() -> "Feedback publication completed — feedbackId=" + feedbackId
                    + " city=" + city + " messageSize=" + messageBody.length());

            // Return success with feedbackId
            FeedbackAcceptedDto acceptedDto = new FeedbackAcceptedDto(
                    feedbackId,
                    "accepted",
                    "Feedback received and queued for processing");
            return new FeedbackResult.Success(acceptedDto);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Feedback publication failed — feedbackId=" + feedbackId
                    + " city=" + city + " error=" + e.getMessage(), e);
            return new FeedbackResult.Error(FeedbackErrorCode.QUEUE_PUBLISH_FAILED,
                    "Failed to publish feedback to queue: " + e.getMessage());
        }
    }
}
