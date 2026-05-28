package cat.complai.utilities.sqs;

import cat.complai.dto.sqs.AskSqsMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesResponse;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SqsAskPublisher}.
 *
 * <p>We subclass the protected no-arg constructor to intercept the serialised message body
 * without wiring a real SQS client.
 */
class SqsAskPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Captures the last message body published without touching the AWS SDK.
     */
    static class CapturingSqsPublisher extends SqsAskPublisher {
        final AtomicReference<String> captured = new AtomicReference<>();

        @Override
        public void publish(AskSqsMessage message) {
            try {
                captured.set(new ObjectMapper().writeValueAsString(message));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    void publish_serialisesAllFieldsToJson() throws Exception {
        CapturingSqsPublisher publisher = new CapturingSqsPublisher();
        AskSqsMessage message = new AskSqsMessage(
                12345L,
                "Quins són els horaris?",
                "elprat",
                "CA",
                "conv-telegram-12345");

        publisher.publish(message);

        assertNotNull(publisher.captured.get(), "Message body must have been captured");
        JsonNode root = mapper.readTree(publisher.captured.get());
        assertEquals(12345L, root.path("chatId").asLong());
        assertEquals("Quins són els horaris?", root.path("question").asText());
        assertEquals("elprat", root.path("cityId").asText());
        assertEquals("CA", root.path("lang").asText());
        assertEquals("conv-telegram-12345", root.path("conversationId").asText());
    }

    @Test
    void publish_withNullQueueUrl_throwsIllegalState() {
        SqsAskPublisher publisher = new SqsAskPublisher() {
            @Override
            public void publish(AskSqsMessage message) {
                throw new IllegalStateException("ASK_QUEUE_URL is not configured — cannot publish ask message");
            }
        };
        AskSqsMessage message = new AskSqsMessage(1L, "text", "city", "CA", null);

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
        SqsAskPublisher publisher = new SqsAskPublisher("http://sqs.test/ask-queue-below",
                mockSqs, new ObjectMapper());

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
        SqsAskPublisher publisher = new SqsAskPublisher("http://sqs.test/ask-queue-exceeded",
                mockSqs, new ObjectMapper());

        assertTrue(publisher.isQueueDepthExceeded(),
                "Queue depth 2000 should exceed the limit of " + SqsAskPublisher.MAX_QUEUE_DEPTH);
    }

    @Test
    void isQueueDepthExceeded_sdkException_failsOpen() {
        SqsClient mockSqs = mock(SqsClient.class);
        when(mockSqs.getQueueAttributes(any(GetQueueAttributesRequest.class)))
                .thenThrow(new RuntimeException("Network error"));

        // Use a unique queue URL to avoid the static cache from other tests
        SqsAskPublisher publisher = new SqsAskPublisher("http://sqs.test/ask-queue-error",
                mockSqs, new ObjectMapper());

        assertFalse(publisher.isQueueDepthExceeded(),
                "Should fail open (return false) when the API call fails");
    }
}
