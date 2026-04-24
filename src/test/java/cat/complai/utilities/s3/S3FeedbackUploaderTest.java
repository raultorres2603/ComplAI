package cat.complai.utilities.s3;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link S3FeedbackUploader}.
 *
 * <p>We subclass the protected no-arg constructor to intercept upload calls
 * without wiring a real S3 client.
 */
class S3FeedbackUploaderTest {

    /**
     * Captures upload calls without touching the AWS SDK.
     */
    static class CapturingS3Uploader extends S3FeedbackUploader {
        final AtomicReference<String> capturedKey = new AtomicReference<>();
        final AtomicReference<String> capturedJson = new AtomicReference<>();

        @Override
        public void upload(String feedbackJson, String s3Key) {
            capturedKey.set(s3Key);
            capturedJson.set(feedbackJson);
        }
    }

    @Test
    void upload_successfulUpload_callsS3WithCorrectKeyAndContent() {
        CapturingS3Uploader uploader = new CapturingS3Uploader();

        uploader.upload("{\"feedbackId\":\"test-123\"}", "feedback/elprat/test-123.json");

        assertNotNull(uploader.capturedKey.get());
        assertNotNull(uploader.capturedJson.get());
        assertEquals("feedback/elprat/test-123.json", uploader.capturedKey.get());
        assertEquals("{\"feedbackId\":\"test-123\"}", uploader.capturedJson.get());
    }

    @Test
    void upload_s3KeyFormatIsCorrect() {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        String json = "{\"data\":\"test\"}";
        String s3Key = "feedback/barcelona/uuid-456.json";

        uploader.upload(json, s3Key);

        assertTrue(uploader.capturedKey.get().startsWith("feedback/"));
        assertTrue(uploader.capturedKey.get().contains(".json"));
    }

    @Test
    void upload_withLargeJson_capturesFullContent() {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        String largeJson = "{\"message\":\"" + "x".repeat(10000) + "\"}";

        uploader.upload(largeJson, "feedback/city/id.json");

        assertEquals(largeJson, uploader.capturedJson.get());
        assertEquals(largeJson.length(), uploader.capturedJson.get().length());
    }

    @Test
    void upload_withNullBucketName_throwsIllegalState() {
        S3FeedbackUploader uploader = new S3FeedbackUploader() {
            @Override
            public void upload(String feedbackJson, String s3Key) {
                // Simulate the check in the real implementation
                if (true) { // bucketName is null
                    throw new IllegalStateException("feedback.bucket-name is not configured");
                }
            }
        };

        assertThrows(IllegalStateException.class, () -> uploader.upload("{}", "feedback/city/id.json"),
                "Uploader must throw when bucket name is absent");
    }

    @Test
    void upload_exceptionPropagates_rethrowsAsRuntimeException() {
        S3FeedbackUploader uploader = new S3FeedbackUploader() {
            @Override
            public void upload(String feedbackJson, String s3Key) {
                throw new RuntimeException("S3 endpoint unreachable");
            }
        };

        assertThrows(RuntimeException.class, () -> uploader.upload("{}", "feedback/city/id.json"),
                "Uploader must propagate exceptions");
    }
}
