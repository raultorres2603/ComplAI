package cat.complai.utilities.s3;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Uploads feedback JSON files to the S3 feedback bucket.
 *
 * <p>
 * The worker Lambda (FeedbackProcessor) uses this to persist feedback
 * submissions.
 * Exceptions are caught and rethrown so the worker can report failures to the
 * SQS DLQ.
 */
@Singleton
public class S3FeedbackUploader {

    private final S3Client s3Client;
    private final String bucketName;

    private final Logger logger = Logger.getLogger(S3FeedbackUploader.class.getName());

    @Inject
    public S3FeedbackUploader(
            @Value("${feedback.bucket-name:}") String bucketName,
            S3Client s3Client) {
        this.bucketName = bucketName;
        this.s3Client = s3Client;
    }

    /**
     * Protected no-arg constructor for test subclassing.
     */
    protected S3FeedbackUploader() {
        this.bucketName = null;
        this.s3Client = null;
    }

    /**
     * Uploads feedback JSON to S3 at the specified key.
     *
     * @param feedbackJson the JSON string to upload
     * @param s3Key        the S3 key (path) where the JSON will be stored
     * @throws RuntimeException if the upload fails
     */
    public void upload(String feedbackJson, String s3Key) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalStateException("feedback.bucket-name is not configured");
        }

        try {
            logger.info(() -> "S3 upload starting — bucket=" + bucketName
                    + " key=" + s3Key + " size=" + feedbackJson.length());

            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(s3Key)
                            .contentType("application/json")
                            .build(),
                    RequestBody.fromString(feedbackJson));

            logger.info(() -> "S3 upload completed — bucket=" + bucketName
                    + " key=" + s3Key);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "S3 upload failed — bucket=" + bucketName
                    + " key=" + s3Key + " error=" + e.getMessage(), e);
            throw new RuntimeException("S3 upload failed: " + e.getMessage(), e);
        }
    }
}
