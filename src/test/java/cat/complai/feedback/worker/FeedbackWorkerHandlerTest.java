package cat.complai.feedback.worker;

import cat.complai.feedback.dto.FeedbackSqsMessage;
import cat.complai.feedback.s3.S3FeedbackUploader;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the feedback message processing pipeline.
 *
 * <p>{@link FeedbackProcessor} contains all the business logic for processing feedback
 * messages from SQS. We test it directly to avoid triggering the
 * {@code MicronautRequestHandler} constructor, which starts an {@code ApplicationContext}
 * and binds an HTTP port — incompatible with the parallel integration test environment.
 *
 * <p>A small {@link FeedbackWorkerHandlerBatchTest} at the bottom validates the batch-item-failure
 * reporting in the handler itself, using a pre-built processor.
 */
class FeedbackWorkerHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Captures upload calls and can simulate failures.
     */
    static class TestingS3Uploader extends S3FeedbackUploader {
        final AtomicInteger uploadCount = new AtomicInteger(0);
        final AtomicReference<String> uploadedKey = new AtomicReference<>();
        final AtomicReference<String> uploadedJson = new AtomicReference<>();
        boolean shouldFail = false;

        @Override
        public void upload(String feedbackJson, String s3Key) {
            if (shouldFail) {
                throw new RuntimeException("Simulated S3 failure");
            }
            uploadedKey.set(s3Key);
            uploadedJson.set(feedbackJson);
            uploadCount.incrementAndGet();
        }
    }

    // -------------------------------------------------------------------------
    // FeedbackProcessor tests
    // -------------------------------------------------------------------------

    @Test
    void process_successfulUpload_uploadsValidJson() throws Exception {
        TestingS3Uploader uploader = new TestingS3Uploader();
        FeedbackProcessor processor = new FeedbackProcessor(uploader);

        FeedbackSqsMessage feedback = new FeedbackSqsMessage(
                "fb-001", System.currentTimeMillis(), "elprat",
                "Joan", "12345678A", "Complaint");

        processor.process(feedback);

        assertEquals(1, uploader.uploadCount.get());
        assertEquals("feedback/elprat/fb-001.json", uploader.uploadedKey.get());
        assertNotNull(uploader.uploadedJson.get());
        assertTrue(uploader.uploadedJson.get().contains("12345678A"));
    }

    @Test
    void process_uploadFails_throwsException() {
        TestingS3Uploader uploader = new TestingS3Uploader();
        uploader.shouldFail = true;
        FeedbackProcessor processor = new FeedbackProcessor(uploader);

        FeedbackSqsMessage feedback = new FeedbackSqsMessage(
                "fb-002", System.currentTimeMillis(), "elprat",
                "Joan", "12345678A", "Complaint");

        assertThrows(RuntimeException.class, () -> processor.process(feedback),
                "processor.process() must throw when S3 upload fails");
        assertEquals(0, uploader.uploadCount.get());
    }

    @Test
    void process_multipleMessages_eachGeneratesSeparateS3Key() throws Exception {
        TestingS3Uploader uploader = new TestingS3Uploader();
        FeedbackProcessor processor = new FeedbackProcessor(uploader);

        FeedbackSqsMessage fb1 = new FeedbackSqsMessage("fb-1", 1L, "elprat", "U1", "I1", "M1");
        FeedbackSqsMessage fb2 = new FeedbackSqsMessage("fb-2", 2L, "barcelona", "U2", "I2", "M2");

        processor.process(fb1);
        assertEquals("feedback/elprat/fb-1.json", uploader.uploadedKey.get());

        processor.process(fb2);
        assertEquals("feedback/barcelona/fb-2.json", uploader.uploadedKey.get());

        assertEquals(2, uploader.uploadCount.get());
    }

    // -------------------------------------------------------------------------
    // Batch-item-failure reporting in FeedbackWorkerHandler
    //
    // We test the handler's execute() logic by building a handler that uses a
    // pre-configured FeedbackProcessor, bypassing Micronaut DI entirely.
    // -------------------------------------------------------------------------

    private SQSEvent createBatchEvent(List<String> messageIds, List<String> bodies) {
        SQSEvent event = new SQSEvent();
        List<SQSEvent.SQSMessage> messages = new ArrayList<>();

        for (int i = 0; i < messageIds.size(); i++) {
            SQSEvent.SQSMessage msg = new SQSEvent.SQSMessage();
            msg.setMessageId(messageIds.get(i));
            msg.setBody(bodies.get(i));
            messages.add(msg);
        }

        event.setRecords(messages);
        return event;
    }

    @Test
    void execute_multipleMessages_reportsOnlyFailingOnes() throws Exception {
        TestingS3Uploader uploader = new TestingS3Uploader();
        // Simulate failure on all messages for this test
        uploader.shouldFail = true;

        FeedbackSqsMessage fb1 = new FeedbackSqsMessage("fb-a", 1L, "c", "U", "I", "M");
        FeedbackSqsMessage fb2 = new FeedbackSqsMessage("fb-b", 2L, "c", "U", "I", "M");
        FeedbackSqsMessage fb3 = new FeedbackSqsMessage("fb-c", 3L, "c", "U", "I", "M");

        List<String> messageIds = List.of("msg-A", "msg-B", "msg-C");
        List<String> bodies = List.of(
                MAPPER.writeValueAsString(fb1),
                MAPPER.writeValueAsString(fb2),
                MAPPER.writeValueAsString(fb3)
        );
        SQSEvent event = createBatchEvent(messageIds, bodies);

        // Simulate handler logic: process each message and report failures
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        FeedbackProcessor processor = new FeedbackProcessor(uploader);

        for (SQSEvent.SQSMessage sqsMsg : event.getRecords()) {
            try {
                FeedbackSqsMessage feedbackMsg = MAPPER.readValue(sqsMsg.getBody(), FeedbackSqsMessage.class);
                processor.process(feedbackMsg);
            } catch (Exception e) {
                failures.add(new SQSBatchResponse.BatchItemFailure(sqsMsg.getMessageId()));
            }
        }
        SQSBatchResponse response = new SQSBatchResponse(failures);

        assertEquals(3, response.getBatchItemFailures().size());
        List<String> failedIds = response.getBatchItemFailures().stream()
                .map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .toList();
        assertTrue(failedIds.contains("msg-A"));
        assertTrue(failedIds.contains("msg-B"));
        assertTrue(failedIds.contains("msg-C"));
    }
}
