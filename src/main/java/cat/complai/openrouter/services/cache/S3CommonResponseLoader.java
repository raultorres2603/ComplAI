package cat.complai.openrouter.services.cache;

import cat.complai.openrouter.cache.CommonResponseEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.core.sync.RequestBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * S3 integration for loading and persisting common AI responses.
 * 
 * Loads `common-ai-requests.json` from S3 instead of classpath, enabling
 * dynamic updates without redeploying Lambda.
 * 
 * PRIVACY & SECURITY:
 * - Handles S3 API errors gracefully (404 -> returns empty list)
 * - Reads/writes only pre-configured common responses (no user data)
 * - S3 bucket versioning enabled for audit trail
 * 
 * Thread-safe: Uses AWS SDK's S3Client (thread-safe singleton) + immutable
 * lists.
 * 
 * Fallback logic:
 * - S3 operation fails -> log warning -> caller uses classpath fallback
 * - S3 key not found -> log info -> return empty list
 */
@Singleton
public class S3CommonResponseLoader {

    private static final Logger LOGGER = Logger.getLogger(S3CommonResponseLoader.class.getName());

    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final String bucketName;
    private final String key;

    public S3CommonResponseLoader(S3Client s3Client, ObjectMapper objectMapper,
            @Value("${complai.common-responses.bucket:}") String bucketName,
            @Value("${complai.common-responses.key:common-ai-requests.json}") String key) {
        this.s3Client = s3Client;
        this.objectMapper = objectMapper;
        this.bucketName = bucketName;
        this.key = key;

        LOGGER.info(() -> String.format(
                "S3CommonResponseLoader initialized: bucket=%s, key=%s",
                bucketName != null && !bucketName.isBlank() ? bucketName : "(disabled)",
                key));
    }

    /**
     * Load common responses from S3.
     * 
     * Deserializes the JSON file into a List of CommonResponseEntry objects.
     * 
     * Error handling:
     * - S3 bucket/key missing: Returns empty list + logs warning
     * - JSON parse error: Returns empty list + logs error
     * - Network/permission error: Throws exception (caller handles fallback)
     * 
     * @return List of CommonResponseEntry (may be empty)
     * @throws Exception if S3 client error or network issue (not 404)
     */
    public List<CommonResponseEntry> loadFromS3() throws Exception {
        if (bucketName == null || bucketName.isBlank()) {
            LOGGER.fine("S3 bucket not configured; skipping S3 load");
            return Collections.emptyList();
        }

        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();

            try (InputStream inputStream = s3Client.getObject(request)) {
                List<CommonResponseEntry> entries = objectMapper.readValue(
                        inputStream,
                        new TypeReference<List<CommonResponseEntry>>() {
                        });

                LOGGER.info(() -> String.format(
                        "Loaded %d common responses from S3: s3://%s/%s",
                        entries.size(), bucketName, key));
                return entries;
            }
        } catch (NoSuchKeyException e) {
            LOGGER.warning(() -> String.format(
                    "S3 key not found: s3://%s/%s (returning empty list)",
                    bucketName, key));
            return Collections.emptyList();
        } catch (IOException e) {
            LOGGER.severe(() -> String.format(
                    "Failed to parse S3 JSON: s3://%s/%s — %s",
                    bucketName, key, e.getMessage()));
            return Collections.emptyList();
        }
    }

    /**
     * Write common responses to S3.
     * 
     * Serializes the list and uploads as JSON to S3. Used by
     * FrequencyPromotionScheduler
     * after merging promoted questions into the common responses list.
     * 
     * @param entries List of CommonResponseEntry to persist
     * @throws Exception if S3 client error
     */
    public void writeToS3(List<CommonResponseEntry> entries) throws Exception {
        if (bucketName == null || bucketName.isBlank()) {
            LOGGER.warning("S3 bucket not configured; skipping S3 write");
            return;
        }

        try {
            String jsonContent = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(entries);
            byte[] contentBytes = jsonContent.getBytes(StandardCharsets.UTF_8);

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType("application/json")
                    .contentLength((long) contentBytes.length)
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(contentBytes));

            LOGGER.info(() -> String.format(
                    "Wrote %d common responses to S3: s3://%s/%s (%d bytes)",
                    entries.size(), bucketName, key, contentBytes.length));
        } catch (IOException e) {
            LOGGER.severe(() -> String.format(
                    "Failed to serialize/write entries to S3: %s",
                    e.getMessage()));
            throw new RuntimeException("Failed to write common responses to S3", e);
        }
    }

    /**
     * Ensure S3 JSON exists; if not, create it with an empty array.
     * Useful during initialization to bootstrap the S3 bucket.
     * 
     * @throws Exception if S3 write fails
     */
    public void ensureS3JsonExists() throws Exception {
        if (bucketName == null || bucketName.isBlank()) {
            LOGGER.fine("S3 bucket not configured; skipping ensureS3JsonExists");
            return;
        }

        try {
            // Try to load; if it exists, we're done
            loadFromS3();
            LOGGER.fine(() -> String.format("S3 key already exists: s3://%s/%s", bucketName, key));
        } catch (Exception e) {
            // Doesn't exist; create it with empty array
            LOGGER.info(() -> String.format(
                    "S3 key not found; initializing with empty array: s3://%s/%s",
                    bucketName, key));
            writeToS3(Collections.emptyList());
        }
    }
}
