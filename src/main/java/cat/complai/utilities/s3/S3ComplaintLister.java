package cat.complai.utilities.s3;

import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lists complaint files from S3 and generates pre-signed URLs for download.
 *
 * <p>This service is used by the statistics endpoint to return complaint files
 * from the last week. Pre-signed URLs are valid for 7 days.
 */
@Singleton
public class S3ComplaintLister {

    private static final Duration PRESIGN_EXPIRY = Duration.ofDays(7);
    private static final String COMPLAINT_KEY_PREFIX = "complaints/";

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;

    private final Logger logger = Logger.getLogger(S3ComplaintLister.class.getName());

    @Inject
    public S3ComplaintLister(
            @Value("${COMPLAINTS_BUCKET:complai-complaints-development}") String bucketName,
            @Value("${COMPLAINTS_REGION:eu-west-1}") String region,
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

        this.s3Client = clientBuilder.build();
        this.s3Presigner = presignerBuilder.build();
    }

    /**
     * Protected no-arg constructor for test subclassing.
     */
    protected S3ComplaintLister() {
        this.bucketName = null;
        this.s3Client = null;
        this.s3Presigner = null;
    }

    /**
     * Lists complaint files from the last 7 days and generates pre-signed URLs.
     *
     * @return list of complaint file entries with pre-signed URLs, or empty list if none found
     */
    public List<ComplaintFileEntry> listComplaintFiles() {
        logger.info(() -> "Listing complaint files from S3 bucket: " + bucketName);

        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        List<ComplaintFileEntry> files = new ArrayList<>();

        try {
            // List objects with the complaints prefix
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(COMPLAINT_KEY_PREFIX)
                    .build();

            ListObjectsV2Response response = s3Client.listObjectsV2(request);

            // DEBUG: Log raw S3 response
            logger.info(() -> "Total objects in S3 response: " + response.contents().size());
            for (S3Object obj : response.contents()) {
                logger.info(() -> "Object key: " + obj.key() + ", lastModified: " + obj.lastModified());
            }

            for (S3Object s3Object : response.contents()) {
                // Filter by last modified date (last 7 days)
                Instant lastModified = s3Object.lastModified();
                // Include only files modified within the last 7 days (i.e., after sevenDaysAgo)
                if (lastModified.isAfter(sevenDaysAgo)) {
                    String key = s3Object.key();
                    String fileName = extractFileName(key);
                    String presignedUrl = generatePresignedUrl(key);

                    files.add(new ComplaintFileEntry(fileName, presignedUrl));
                    logger.info(() -> "Found complaint file: " + fileName + " (lastModified: " + lastModified + ")");
                }
            }

            logger.info(() -> "Total complaint files found: " + files.size());
            return files;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error listing complaint files from S3: " + e.getMessage(), e);
            throw new RuntimeException("Failed to list complaint files: " + e.getMessage(), e);
        }
    }

    /**
     * Generates a pre-signed URL for the given S3 key.
     */
    private String generatePresignedUrl(String key) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(PRESIGN_EXPIRY)
                .getObjectRequest(r -> r.bucket(bucketName).key(key))
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Extracts the file name from an S3 key.
     * For example: "complaints/elprat/1700000000-complaint.pdf" -> "1700000000-complaint.pdf"
     */
    private String extractFileName(String key) {
        int lastSlash = key.lastIndexOf('/');
        return lastSlash >= 0 ? key.substring(lastSlash + 1) : key;
    }

    /**
     * Closes the S3Client and S3Presigner when the bean is destroyed.
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

    /**
     * Simple data class for complaint file entries returned by listComplaintFiles.
     */
    public static class ComplaintFileEntry {
        private final String fileName;
        private final String url;

        public ComplaintFileEntry(String fileName, String url) {
            this.fileName = fileName;
            this.url = url;
        }

        public String getFileName() {
            return fileName;
        }

        public String getUrl() {
            return url;
        }
    }
}