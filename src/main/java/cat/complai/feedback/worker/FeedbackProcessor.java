package cat.complai.feedback.worker;

import cat.complai.feedback.dto.FeedbackSqsMessage;
import cat.complai.feedback.s3.S3FeedbackUploader;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Non-Micronaut helper for processing feedback messages.
 *
 * <p>
 * Isolated from Micronaut context for testability. The worker Lambda
 * instantiates
 * this and calls {@link #process(FeedbackSqsMessage)} for each SQS message.
 *
 * <p>
 * If processing fails, the exception is rethrown and the worker reports the
 * message
 * ID as a batch item failure for DLQ routing and retry.
 */
public class FeedbackProcessor {

    private final S3FeedbackUploader s3Uploader;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Logger logger = Logger.getLogger(FeedbackProcessor.class.getName());

    public FeedbackProcessor(S3FeedbackUploader s3Uploader) {
        this.s3Uploader = s3Uploader;
    }

    /**
     * Processes a single feedback message: serializes to JSON and uploads to S3.
     *
     * @param message the deserialized SQS message
     * @throws Exception if serialization or upload fails
     */
    public void process(FeedbackSqsMessage message) throws Exception {
        logger.info(() -> "Feedback processing starting — feedbackId=" + message.feedbackId()
                + " city=" + message.city());

        try {
            // Generate S3 key based on city and feedbackId
            String s3Key = generateS3Key(message);

            // Serialize the feedback message to JSON
            String feedbackJson = mapper.writeValueAsString(message);

            // Upload to S3
            s3Uploader.upload(feedbackJson, s3Key);

            logger.info(() -> "Feedback processing completed — feedbackId=" + message.feedbackId()
                    + " s3Key=" + s3Key);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Feedback processing failed — feedbackId=" + message.feedbackId()
                    + " error=" + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Generates the S3 key path for storing feedback JSON.
     *
     * <p>
     * Format: feedback/{city}/{feedbackId}.json
     */
    private String generateS3Key(FeedbackSqsMessage message) {
        return "feedback/" + message.city() + "/" + message.feedbackId() + ".json";
    }
}
