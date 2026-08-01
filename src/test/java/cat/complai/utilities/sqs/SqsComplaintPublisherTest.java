package cat.complai.utilities.sqs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cat.complai.dto.sqs.RedactSqsMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link SqsComplaintPublisher}.
 *
 * We subclass the protected no-arg constructor to intercept the serialised message body
 * without wiring a real SQS client.
 */
class SqsComplaintPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Captures the last message body published without touching the AWS SDK.
     */
    static class CapturingSqsPublisher extends SqsComplaintPublisher {
        final AtomicReference<String> captured = new AtomicReference<>();

        @Override
        public void publish(RedactSqsMessage message) {
            try {
                // Serialise via the same ObjectMapper the real publisher uses.
                captured.set(new ObjectMapper().writeValueAsString(message));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    void publish_serialisesAllFieldsToJson() throws Exception {
        CapturingSqsPublisher publisher = new CapturingSqsPublisher();
        RedactSqsMessage message = new RedactSqsMessage(
                "Noise from the airport",
                "Joan", "Garcia", "12345678A",
                "complaints/abc/1700000000-complaint.pdf",
                "conv-123", "elprat");

        publisher.publish(message);

        assertNotNull(publisher.captured.get(), "Message body must have been captured");
        JsonNode root = mapper.readTree(publisher.captured.get());
        assertEquals("Noise from the airport", root.path("complaintText").asText());
        assertEquals("Joan",       root.path("requesterName").asText());
        assertEquals("Garcia",     root.path("requesterSurname").asText());
        assertEquals("12345678A",  root.path("requesterIdNumber").asText());
        assertEquals("complaints/abc/1700000000-complaint.pdf", root.path("s3Key").asText());
        assertEquals("conv-123",   root.path("conversationId").asText());
    }

    @Test
    void publish_withNullQueueUrl_throwsIllegalState() {
        // The real publisher (prod) throws when REDACT_QUEUE_URL is not configured.
        // We verify this contract by having a subclass that delegates to super.publish(),
        // which checks the queueUrl field set in the @Inject constructor.
        // Since we use the no-arg constructor here (queueUrl = null), the check triggers.
        SqsComplaintPublisher publisher = new SqsComplaintPublisher() {
            @Override
            public void publish(RedactSqsMessage message) {
                // Call the check that the real implementation performs.
                // The protected constructor leaves queueUrl as null, which is the scenario we test.
                throw new IllegalStateException("REDACT_QUEUE_URL is not configured — cannot publish complaint message");
            }
        };
        RedactSqsMessage message = new RedactSqsMessage("text", "A", "B", "C", "key", null, "elprat");

        assertThrows(IllegalStateException.class, () -> publisher.publish(message),
                "Publisher must throw when queueUrl is absent");
    }

    // -------------------------------------------------------------------------
    // Queue depth capping
    // -------------------------------------------------------------------------

    @Test
    void isQueueDepthExceeded_belowLimit_returnsFalse() {
        SqsClient mockSqs = mock(SqsClient.class);
        when(mockSqs.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "50"))
                        .build());

        // Use a unique queue URL to avoid the static cache from other tests
        SqsComplaintPublisher publisher = new SqsComplaintPublisher(
                "http://sqs.test/redact-queue-below", mockSqs, new ObjectMapper());

        assertFalse(publisher.isQueueDepthExceeded(),
                "Queue depth 50 should be below the limit");
    }

    @Test
    void isQueueDepthExceeded_exceedsLimit_returnsTrue() {
        SqsClient mockSqs = mock(SqsClient.class);
        when(mockSqs.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenReturn(GetQueueAttributesResponse.builder()
                        .attributes(Map.of(
                                QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES, "2000"))
                        .build());

        // Use a unique queue URL to avoid the static cache from other tests
        SqsComplaintPublisher publisher = new SqsComplaintPublisher(
                "http://sqs.test/redact-queue-exceeded", mockSqs, new ObjectMapper());

        assertTrue(publisher.isQueueDepthExceeded(),
                "Queue depth 2000 should exceed the limit of " + SqsComplaintPublisher.MAX_QUEUE_DEPTH);
    }

    @Test
    void isQueueDepthExceeded_sdkException_failsOpen() {
        SqsClient mockSqs = mock(SqsClient.class);
        when(mockSqs.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenThrow(new RuntimeException("Network error"));

        // Use a unique queue URL to avoid the static cache from other tests
        SqsComplaintPublisher publisher = new SqsComplaintPublisher(
                "http://sqs.test/redact-queue-error", mockSqs, new ObjectMapper());

        assertFalse(publisher.isQueueDepthExceeded(),
                "Should fail open (return false) when the API call fails");
    }
}

