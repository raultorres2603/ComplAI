package cat.complai.feedback.worker;

import cat.complai.feedback.dto.FeedbackSqsMessage;
import cat.complai.feedback.s3.S3FeedbackUploader;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FeedbackProcessor}.
 *
 * <p>Tests the processor logic for serializing and uploading feedback to S3,
 * using a capturing uploader to avoid AWS SDK calls.
 */
class FeedbackProcessorTest {

    /**
     * Captures upload method calls for verification.
     */
    static class CapturingS3Uploader extends S3FeedbackUploader {
        final AtomicReference<String> capturedJson = new AtomicReference<>();
        final AtomicReference<String> capturedKey = new AtomicReference<>();

        @Override
        public void upload(String feedbackJson, String s3Key) {
            capturedJson.set(feedbackJson);
            capturedKey.set(s3Key);
        }
    }

    @Test
    void process_successfulUpload_uploadsJsonToS3WithCorrectKey() throws Exception {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        FeedbackProcessor processor = new FeedbackProcessor(uploader);

        FeedbackSqsMessage message = new FeedbackSqsMessage(
                "fb-123", System.currentTimeMillis(), "elprat",
                "Joan Garcia", "12345678A", "Noise complaint");

        processor.process(message);

        assertNotNull(uploader.capturedJson.get(), "JSON must have been uploaded");
        assertNotNull(uploader.capturedKey.get(), "S3 key must have been set");

        // Verify S3 key format
        String expectedKey = "feedback/elprat/fb-123.json";
        assertEquals(expectedKey, uploader.capturedKey.get());
    }

    @Test
    void process_jsonContainsAllFields() throws Exception {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        FeedbackProcessor processor = new FeedbackProcessor(uploader);

        FeedbackSqsMessage message = new FeedbackSqsMessage(
                "fb-456", 1700000000000L, "barcelona",
                "Joan", "87654321B", "Message content");

        processor.process(message);

        String uploadedJson = uploader.capturedJson.get();
        assertNotNull(uploadedJson);
        assertTrue(uploadedJson.contains("fb-456"), "feedbackId must be in JSON");
        assertTrue(uploadedJson.contains("barcelona"), "city must be in JSON");
        assertTrue(uploadedJson.contains("Joan"), "userName must be in JSON");
        assertTrue(uploadedJson.contains("87654321B"), "idUser must be in JSON");
        assertTrue(uploadedJson.contains("Message content"), "message must be in JSON");
    }

    @Test
    void process_s3KeyFollowsPattern() throws Exception {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        FeedbackProcessor processor = new FeedbackProcessor(uploader);

        FeedbackSqsMessage message = new FeedbackSqsMessage(
                "uuid-pattern", 1234567890000L, "testcity",
                "User", "ID123", "Test");

        processor.process(message);

        String s3Key = uploader.capturedKey.get();
        assertTrue(s3Key.startsWith("feedback/"), "Key must start with 'feedback/'");
        assertTrue(s3Key.contains("testcity"), "Key must contain city");
        assertTrue(s3Key.contains("uuid-pattern"), "Key must contain feedbackId");
        assertTrue(s3Key.endsWith(".json"), "Key must end with .json");
    }

    @Test
    void process_uploaderException_propagates() throws Exception {
        S3FeedbackUploader failingUploader = new S3FeedbackUploader() {
            @Override
            public void upload(String feedbackJson, String s3Key) {
                throw new RuntimeException("S3 connection failed");
            }
        };

        FeedbackProcessor processor = new FeedbackProcessor(failingUploader);
        FeedbackSqsMessage message = new FeedbackSqsMessage(
                "fb-789", System.currentTimeMillis(), "elprat",
                "User", "ID", "Message");

        assertThrows(RuntimeException.class, () -> processor.process(message),
                "Uploader exceptions must propagate for DLQ routing");
    }

    @Test
    void process_multipleMessages_eachGeneratesUniqueS3Key() throws Exception {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        FeedbackProcessor processor = new FeedbackProcessor(uploader);

        FeedbackSqsMessage msg1 = new FeedbackSqsMessage(
                "id-1", 1000L, "city", "User", "ID", "Msg");
        processor.process(msg1);
        String key1 = uploader.capturedKey.get();

        FeedbackSqsMessage msg2 = new FeedbackSqsMessage(
                "id-2", 2000L, "city", "User", "ID", "Msg");
        processor.process(msg2);
        String key2 = uploader.capturedKey.get();

        assertNotEquals(key1, key2, "Each message should have a unique S3 key");
        assertTrue(key1.contains("id-1"));
        assertTrue(key2.contains("id-2"));
    }

    @Test
    void process_cityWithPathTraversal_sanitizesKey() throws Exception {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        FeedbackProcessor processor = new FeedbackProcessor(uploader);

        FeedbackSqsMessage message = new FeedbackSqsMessage(
                "fb-999", System.currentTimeMillis(), "../evil",
                "User", "ID", "Msg");

        processor.process(message);

        String capturedKey = uploader.capturedKey.get();
        assertFalse(capturedKey.contains(".."), "Sanitized key must not contain '..'");
        assertTrue(capturedKey.startsWith("feedback/"), "Key must start with 'feedback/'");
    }

    @Test
    void process_cityWithSpecialChars_sanitizesKey() throws Exception {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        FeedbackProcessor processor = new FeedbackProcessor(uploader);

        FeedbackSqsMessage message = new FeedbackSqsMessage(
                "fb-888", System.currentTimeMillis(), "el prat/test",
                "User", "ID", "Msg");

        processor.process(message);

        String capturedKey = uploader.capturedKey.get();
        // Key format: feedback/{safeCity}/{feedbackId}.json — only 2 slashes after sanitization
        long slashCount = capturedKey.chars().filter(c -> c == '/').count();
        assertEquals(2, slashCount, "Key must have exactly 2 slashes (feedback/safeCity/feedbackId.json)");
        assertFalse(capturedKey.contains(" "), "Key must not contain spaces");
    }

    @Test
    void process_nullCity_usesUnknown() throws Exception {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        FeedbackProcessor processor = new FeedbackProcessor(uploader);

        FeedbackSqsMessage message = new FeedbackSqsMessage(
                "fb-000", System.currentTimeMillis(), null,
                "User", "ID", "Msg");

        processor.process(message);

        String capturedKey = uploader.capturedKey.get();
        assertTrue(capturedKey.startsWith("feedback/unknown/"), "Null city must map to 'unknown' in S3 key");
    }
}
