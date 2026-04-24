package cat.complai.utilities.s3;

import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * S3 operations for the async complaint letter flow.
 *
 * <p>The API Lambda uses {@link #generatePresignedGetUrl(String)} to create a 24-hour
 * pre-signed GET URL that it returns to the caller before the PDF exists. The worker
 * Lambda uses {@link #upload(String, byte[])} to place the finished PDF at the same key.
 *
 * <p>Both operations share this singleton so the S3 client is initialised once per
 * Lambda invocation lifecycle.
 */
@Singleton
public class S3PdfUploader {

    private static final Duration PRESIGN_EXPIRY = Duration.ofHours(24);

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;

    private final Logger logger = Logger.getLogger(S3PdfUploader.class.getName());

    @Inject
    public S3PdfUploader(
            @Value("${COMPLAINTS_BUCKET:complai-complaints-development}") String bucketName,
            @Value("${COMPLAINTS_REGION:eu-west-1}") String region,
            // Optional LocalStack / test override. Empty string means "use the default AWS endpoint".
            @Value("${AWS_ENDPOINT_URL:}") String endpointUrl) {
        this.bucketName = bucketName;
        Region awsRegion = Region.of(region);

        S3ClientBuilder clientBuilder = S3Client.builder().region(awsRegion);
        software.amazon.awssdk.services.s3.presigner.S3Presigner.Builder presignerBuilder =
                S3Presigner.builder().region(awsRegion);

        if (endpointUrl != null && !endpointUrl.isBlank()) {
            URI endpoint = URI.create(endpointUrl);
            clientBuilder.endpointOverride(endpoint);
            presignerBuilder.endpointOverride(endpoint);
        }

        this.s3Client   = clientBuilder.build();
        this.s3Presigner = presignerBuilder.build();
    }

    /**
     * Protected no-arg constructor so tests can subclass without triggering AWS client
     * initialisation. Follows the same pattern as {@code HttpWrapper}.
     */
    protected S3PdfUploader() {
        this.bucketName  = null;
        this.s3Client    = null;
        this.s3Presigner = null;
    }

    /**
     * Generates a pre-signed S3 GET URL for the given key.
     * The URL is valid for {@value #PRESIGN_EXPIRY} hours regardless of whether the
     * object exists at the time of signing.
     */
    public String generatePresignedGetUrl(String key) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(PRESIGN_EXPIRY)
                .getObjectRequest(r -> r.bucket(bucketName).key(key))
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Uploads PDF bytes to the given key in the complaints bucket.
     * Throws {@link RuntimeException} on failure so the SQS handler can return the
     * message to the queue for a retry.
     */
    public void upload(String key, byte[] pdfBytes) {
        logger.info(() -> "S3 upload starting — bucket=" + bucketName + " key=" + key + " sizeBytes=" + pdfBytes.length);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType("application/pdf")
                .contentLength((long) pdfBytes.length)
                .contentDisposition("inline; filename=\"complaint.pdf\"")
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(pdfBytes));
            logger.info(() -> "S3 upload completed — bucket=" + bucketName + " key=" + key + " sizeBytes=" + pdfBytes.length);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "S3 upload failed — bucket=" + bucketName + " key=" + key
                    + " sizeBytes=" + pdfBytes.length + " error=" + e.getMessage(), e);
            throw new RuntimeException("S3 upload failed for key '" + key + "': " + e.getMessage(), e);
        }
    }

    /**
     * Closes the S3Client and S3Presigner when the bean is destroyed.
     * Prevents resource leaks in warm Lambda invocations.
     */
    @PreDestroy
    public void close() {
        try {
            if (s3Client != null) {
                s3Client.close();
                logger.info("S3Client closed");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to close S3Client", e);
        }
        try {
            if (s3Presigner != null) {
                s3Presigner.close();
                logger.info("S3Presigner closed");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to close S3Presigner", e);
        }
    }
}

