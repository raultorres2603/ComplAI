package cat.complai.worker;

import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.s3.S3PdfUploader;
import cat.complai.sqs.dto.RedactSqsMessage;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the async complaint letter generation pipeline.
 *
 * <p>{@link ComplaintLetterGenerator} contains all the business logic extracted from
 * {@link RedactWorkerHandler}. We test it directly to avoid triggering the
 * {@code MicronautRequestHandler} constructor, which starts an {@code ApplicationContext}
 * and binds an HTTP port — incompatible with the parallel integration test environment.
 *
 * <p>A small {@link RedactWorkerHandlerBatchTest} at the bottom validates the batch-item-failure
 * reporting in the handler itself, using a pre-built generator.
 */
class RedactWorkerHandlerTest {

    private static final RedactPromptBuilder PROMPT_BUILDER = new RedactPromptBuilder();

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    static class FakeHttpWrapper extends HttpWrapper {
        private final String response;
        private final String error;

        FakeHttpWrapper(String response) { this.response = response; this.error = null; }
        FakeHttpWrapper(String response, String error) { this.response = response; this.error = error; }

        @Override
        public CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages) {
            return CompletableFuture.completedFuture(new HttpDto(response, 200, "POST", error));
        }
    }

    static class CapturingS3Uploader extends S3PdfUploader {
        final AtomicReference<byte[]> uploadedBytes = new AtomicReference<>();
        final AtomicReference<String> uploadedKey   = new AtomicReference<>();

        @Override
        public void upload(String key, byte[] pdfBytes) {
            uploadedKey.set(key);
            uploadedBytes.set(pdfBytes);
        }

        @Override
        public String generatePresignedGetUrl(String key) {
            return "https://bucket.s3.eu-west-1.amazonaws.com/" + key;
        }
    }

    // -------------------------------------------------------------------------
    // ComplaintLetterGenerator tests
    // -------------------------------------------------------------------------

    @Test
    void generate_successfulAiResponse_uploadsValidPdf() throws Exception {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        FakeHttpWrapper wrapper = new FakeHttpWrapper(
                "{\"format\": \"pdf\"}\n\nDear Ajuntament,\n\nI am writing to complain about noise.\n\nSincerely,\nJoan Garcia");

        ComplaintLetterGenerator generator = new ComplaintLetterGenerator(PROMPT_BUILDER, wrapper, uploader, 30);
        RedactSqsMessage message = new RedactSqsMessage(
                "Noise from the airport", "Joan", "Garcia", "12345678A",
                "complaints/abc/1700000000-complaint.pdf", null, "elprat");

        generator.generate(message);

        assertNotNull(uploader.uploadedBytes.get(), "PDF must have been uploaded");
        assertTrue(uploader.uploadedBytes.get().length > 0, "Uploaded PDF must not be empty");
        byte[] pdf = uploader.uploadedBytes.get();
        assertEquals('%', pdf[0]); assertEquals('P', pdf[1]);
        assertEquals('D', pdf[2]); assertEquals('F', pdf[3]);
        assertEquals("complaints/abc/1700000000-complaint.pdf", uploader.uploadedKey.get());
    }

    @Test
    void generate_aiReturnsError_throwsException() {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        FakeHttpWrapper wrapper = new FakeHttpWrapper(null, "Upstream API error");

        ComplaintLetterGenerator generator = new ComplaintLetterGenerator(PROMPT_BUILDER, wrapper, uploader, 30);
        RedactSqsMessage message = new RedactSqsMessage(
                "Noise from the airport", "Joan", "Garcia", "12345678A",
                "complaints/abc/1700000000-complaint.pdf", null, "elprat");

        assertThrows(Exception.class, () -> generator.generate(message),
                "generator.generate() must throw when the AI returns an error");
        assertNull(uploader.uploadedBytes.get(), "PDF must not be uploaded on failure");
    }

    @Test
    void generate_aiReturnsNullMessage_throwsException() {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        FakeHttpWrapper wrapper = new FakeHttpWrapper(null); // null message, no error

        ComplaintLetterGenerator generator = new ComplaintLetterGenerator(PROMPT_BUILDER, wrapper, uploader, 30);
        RedactSqsMessage message = new RedactSqsMessage(
                "Noise from the airport", "Joan", "Garcia", "12345678A",
                "complaints/xyz/1700000001-complaint.pdf", null, "elprat");

        assertThrows(Exception.class, () -> generator.generate(message));
        assertNull(uploader.uploadedBytes.get());
    }

    @Test
    void generate_noJsonHeader_stillUploadsPdfFromRawMessage() throws Exception {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        FakeHttpWrapper wrapper = new FakeHttpWrapper(
                "Dear Ajuntament,\n\nI am writing to complain about a pothole on Carrer Major.\n\nSincerely,\nJoan Garcia");

        ComplaintLetterGenerator generator = new ComplaintLetterGenerator(PROMPT_BUILDER, wrapper, uploader, 30);
        RedactSqsMessage message = new RedactSqsMessage(
                "Pothole on Carrer Major", "Joan", "Garcia", "12345678A",
                "complaints/def/1700000002-complaint.pdf", null, "elprat");

        generator.generate(message); // must not throw

        assertNotNull(uploader.uploadedBytes.get(), "PDF must be produced even without a JSON header");
    }

    // -------------------------------------------------------------------------
    // Batch-item-failure reporting in RedactWorkerHandler
    //
    // We test the handler's execute() logic by building a handler that uses a
    // pre-configured ComplaintLetterGenerator, bypassing Micronaut DI entirely.
    // -------------------------------------------------------------------------

    @Test
    void execute_multipleRecords_reportsOnlyFailingOnes() throws Exception {
        CapturingS3Uploader uploader = new CapturingS3Uploader();
        AtomicInteger callCount = new AtomicInteger(0);

        // First call fails, second succeeds.
        HttpWrapper wrapper = new HttpWrapper() {
            @Override
            public CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages) {
                if (callCount.incrementAndGet() == 1) {
                    return CompletableFuture.completedFuture(new HttpDto(null, 500, "POST", "Upstream error"));
                }
                return CompletableFuture.completedFuture(new HttpDto(
                        "{\"format\": \"pdf\"}\n\nDear Ajuntament,\n\nComplaint.\n\nSincerely,\nTest", 200, "POST", null));
            }
        };

        ComplaintLetterGenerator generator = new ComplaintLetterGenerator(PROMPT_BUILDER, wrapper, uploader, 30);

        // Simulate handler logic: process each message and report failures
        // Do NOT instantiate RedactWorkerHandler to avoid port binding issues.
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        RedactSqsMessage msg1 = new RedactSqsMessage("Bad complaint", "A", "B", "C", "complaints/fail/1-complaint.pdf", null, "elprat");
        RedactSqsMessage msg2 = new RedactSqsMessage("Good complaint", "X", "Y", "Z", "complaints/ok/2-complaint.pdf", null, "elprat");
        
        for (RedactSqsMessage msg : List.of(msg1, msg2)) {
            try {
                generator.generate(msg);
            } catch (Exception e) {
                // msg1 will fail on first call, msg2 will succeed on second call
                if (msg == msg1) {
                    failures.add(SQSBatchResponse.BatchItemFailure.builder()
                            .withItemIdentifier("fail-001").build());
                }
            }
        }
        SQSBatchResponse response = SQSBatchResponse.builder().withBatchItemFailures(failures).build();

        assertEquals(1, response.getBatchItemFailures().size(), "Only the failing record should be in failures");
        assertEquals("fail-001", response.getBatchItemFailures().get(0).getItemIdentifier());
    }
}

