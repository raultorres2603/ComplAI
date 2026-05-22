package cat.complai.utilities.s3;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lists feedback files from S3 and generates pre-signed URLs for download.
 *
 * <p>This service is used by the feedback statistics endpoint to return feedback files
 * from the last week for a specific city. Pre-signed URLs are valid for 7 days.
 */
@Singleton
public class S3FeedbackLister {

    private static final Duration PRESIGN_EXPIRY = Duration.ofDays(7);
    private static final String FEEDBACK_KEY_PREFIX = "feedback/";

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucketName;

    private final Logger logger = Logger.getLogger(S3FeedbackLister.class.getName());

    @Inject
    public S3FeedbackLister(
            @Value("${FEEDBACK_BUCKET_NAME:}") String bucketName,
            S3Client s3Client,
            S3Presigner s3Presigner) {
        this.bucketName = bucketName;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    /**
     * Protected no-arg constructor for test subclassing.
     */
    protected S3FeedbackLister() {
        this.bucketName = null;
        this.s3Client = null;
        this.s3Presigner = null;
    }

    /**
     * Lists all feedback files from all cities in the last 7 days and generates pre-signed URLs.
     *
     * @return list of feedback file entries with pre-signed URLs, or empty list if none found
     */
    public List<FeedbackFileEntry> listAllFeedbackFiles() {
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        return listAllFeedbackFiles(sevenDaysAgo, Instant.now());
    }

    /**
     * Lists all feedback files from all cities from a specific date range and generates pre-signed URLs.
     *
     * @param from start of date range (inclusive)
     * @param to end of date range (inclusive)
     * @return list of feedback file entries with pre-signed URLs, or empty list if none found
     */
    public List<FeedbackFileEntry> listAllFeedbackFiles(Instant from, Instant to) {
        logger.info(() -> "Listing all feedback files from S3 bucket: " + bucketName + " from=" + from + " to=" + to);

        List<FeedbackFileEntry> files = new ArrayList<>();

        try {
            // List objects with the feedback prefix (all cities)
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(FEEDBACK_KEY_PREFIX)
                    .build();

            ListObjectsV2Response response = s3Client.listObjectsV2(request);

            // DEBUG: Log raw S3 response
            logger.info(() -> "Total objects in S3 response: " + response.contents().size());
            for (S3Object obj : response.contents()) {
                logger.info(() -> "Object key: " + obj.key() + ", lastModified: " + obj.lastModified());
            }

            for (S3Object s3Object : response.contents()) {
                // Filter by last modified date within the specified range
                Instant lastModified = s3Object.lastModified();
                // Include only files modified within the date range (from inclusive, to inclusive)
                if (!lastModified.isBefore(from) && !lastModified.isAfter(to)) {
                    String key = s3Object.key();
                    String fileName = extractFileName(key);
                    String presignedUrl = generatePresignedUrl(key);

                    files.add(new FeedbackFileEntry(fileName, presignedUrl));
                    logger.info(() -> "Found feedback file: " + fileName + " (lastModified: " + lastModified + ")");
                }
            }

            logger.info(() -> "Total feedback files found: " + files.size());
            return files;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error listing feedback files from S3: " + e.getMessage(), e);
            throw new RuntimeException("Failed to list feedback files: " + e.getMessage(), e);
        }
    }

    /**
     * Lists feedback files for a specific city from the last 7 days and generates pre-signed URLs.
     *
     * @param cityId the city identifier (e.g., "elprat", "barcelona")
     * @return list of feedback file entries with pre-signed URLs, or empty list if none found
     */
    public List<FeedbackFileEntry> listFeedbackFiles(String cityId) {
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        return listFeedbackFiles(cityId, sevenDaysAgo, Instant.now());
    }

    /**
     * Lists feedback files for a specific city from a specific date range and generates pre-signed URLs.
     *
     * @param cityId the city identifier (e.g., "elprat", "barcelona")
     * @param from start of date range (inclusive)
     * @param to end of date range (inclusive)
     * @return list of feedback file entries with pre-signed URLs, or empty list if none found
     */
    public List<FeedbackFileEntry> listFeedbackFiles(String cityId, Instant from, Instant to) {
        logger.info(() -> "Listing feedback files from S3 bucket: " + bucketName + " city=" + cityId + " from=" + from + " to=" + to);

        List<FeedbackFileEntry> files = new ArrayList<>();

        try {
            // Build prefix: feedback/<cityId>/
            String prefix = FEEDBACK_KEY_PREFIX + cityId + "/";

            // List objects with the city-specific prefix
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response response = s3Client.listObjectsV2(request);

            // DEBUG: Log raw S3 response
            logger.info(() -> "Total objects in S3 response: " + response.contents().size());
            for (S3Object obj : response.contents()) {
                logger.info(() -> "Object key: " + obj.key() + ", lastModified: " + obj.lastModified());
            }

            for (S3Object s3Object : response.contents()) {
                // Filter by last modified date within the specified range
                Instant lastModified = s3Object.lastModified();
                // Include only files modified within the date range (from inclusive, to inclusive)
                if (!lastModified.isBefore(from) && !lastModified.isAfter(to)) {
                    String key = s3Object.key();
                    String fileName = extractFileName(key);
                    String presignedUrl = generatePresignedUrl(key);

                    files.add(new FeedbackFileEntry(fileName, presignedUrl));
                    logger.info(() -> "Found feedback file: " + fileName + " (lastModified: " + lastModified + ")");
                }
            }

            logger.info(() -> "Total feedback files found: " + files.size());
            return files;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error listing feedback files from S3: " + e.getMessage(), e);
            throw new RuntimeException("Failed to list feedback files: " + e.getMessage(), e);
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
     * For example: "feedback/elprat/1700000000-feedback.json" -> "1700000000-feedback.json"
     */
    private String extractFileName(String key) {
        int lastSlash = key.lastIndexOf('/');
        return lastSlash >= 0 ? key.substring(lastSlash + 1) : key;
    }

    /**
     * Simple data class for feedback file entries returned by listFeedbackFiles.
     */
    public static class FeedbackFileEntry {
        private final String fileName;
        private final String url;

        public FeedbackFileEntry(String fileName, String url) {
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