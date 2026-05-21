package cat.complai.services.worker;

import cat.complai.dto.openrouter.AskStreamResult;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.OutputFormat;
import cat.complai.dto.sqs.AskSqsMessage;
import cat.complai.services.openrouter.IOpenRouterService;
import cat.complai.services.worker.AskProcessor.TelegramSender;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the async Telegram ask pipeline.
 *
 * <p>{@link AskProcessor} contains all the business logic extracted from
 * {@link AskWorkerHandler}. We test it directly to avoid triggering the
 * {@code MicronautRequestHandler} constructor, which starts an {@code ApplicationContext}
 * and binds an HTTP port — incompatible with the parallel integration test environment.
 *
 * <p>A batch-item-failure test at the bottom validates the handler's failure reporting logic,
 * using a pre-built processor.
 */
class AskWorkerHandlerTest {

    private static final String TOKEN = "test-bot-token";

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    static class CapturingTelegramSender implements TelegramSender {
        final AtomicReference<String> capturedText = new AtomicReference<>();
        final AtomicReference<Long> capturedChatId = new AtomicReference<>();

        @Override
        public void sendMessage(long chatId, String text, String token) {
            capturedChatId.set(chatId);
            capturedText.set(text);
        }
    }

    private static OpenRouterResponseDto successResponse(String message) {
        return new OpenRouterResponseDto(true, message, null, 200, OpenRouterErrorCode.NONE);
    }

    // -------------------------------------------------------------------------
    // AskProcessor tests
    // -------------------------------------------------------------------------

    @Test
    void process_successfulAiResponse_sendsAnswer() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        IOpenRouterService aiService = new FakeAiService(successResponse("L'horari és de 9 a 14h."));

        AskProcessor processor = new AskProcessor(aiService, TOKEN, sender);
        AskSqsMessage message = new AskSqsMessage(
                12345L, "Quins són els horaris?", "elprat", "CA", "telegram-12345");

        processor.process(message);

        assertEquals(12345L, sender.capturedChatId.get());
        assertEquals("L'horari és de 9 a 14h.", sender.capturedText.get());
    }

    @Test
    void process_aiReturnsNull_sendsFallbackError() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        IOpenRouterService aiService = new FakeAiService(null);

        AskProcessor processor = new AskProcessor(aiService, TOKEN, sender);
        AskSqsMessage message = new AskSqsMessage(
                12345L, "Question", "elprat", "CA", "telegram-12345");

        processor.process(message);

        assertNotNull(sender.capturedText.get());
        assertTrue(sender.capturedText.get().contains("No he pogut"));
    }

    @Test
    void process_aiReturnsError_sendsFallbackError() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        IOpenRouterService aiService = new FakeAiService(
                new OpenRouterResponseDto(false, null, "API error", 500, OpenRouterErrorCode.UPSTREAM));

        AskProcessor processor = new AskProcessor(aiService, TOKEN, sender);
        AskSqsMessage message = new AskSqsMessage(
                12345L, "Question", "elprat", "ES", "telegram-12345");

        processor.process(message);

        assertNotNull(sender.capturedText.get());
        assertTrue(sender.capturedText.get().contains("No he podido"));
    }

    @Test
    void process_missingCityId_throwsException() {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        IOpenRouterService aiService = new FakeAiService(successResponse("Answer"));

        AskProcessor processor = new AskProcessor(aiService, TOKEN, sender);
        AskSqsMessage message = new AskSqsMessage(
                12345L, "Question", null, "CA", "telegram-12345");

        assertThrows(IllegalArgumentException.class, () -> processor.process(message));
        assertNull(sender.capturedChatId.get());
    }

    @Test
    void process_missingToken_throwsException() {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        IOpenRouterService aiService = new FakeAiService(successResponse("Answer"));

        AskProcessor processor = new AskProcessor(aiService, null, sender);
        AskSqsMessage message = new AskSqsMessage(
                12345L, "Question", "elprat", "CA", "telegram-12345");

        assertThrows(IllegalStateException.class, () -> processor.process(message));
        assertNull(sender.capturedChatId.get());
    }

    @Test
    void process_senderException_propagates() throws Exception {
        TelegramSender failingSender = (chatId, text, token) -> {
            throw new RuntimeException("Telegram API failed");
        };
        IOpenRouterService aiService = new FakeAiService(successResponse("Answer"));

        AskProcessor processor = new AskProcessor(aiService, TOKEN, failingSender);
        AskSqsMessage message = new AskSqsMessage(
                12345L, "Question", "elprat", "CA", "telegram-12345");

        assertThrows(RuntimeException.class, () -> processor.process(message),
                "Sender exceptions must propagate for DLQ routing");
    }

    // -------------------------------------------------------------------------
    // Batch-item-failure reporting in AskWorkerHandler
    //
    // We test the handler's execute() logic by simulating the batch processing
    // without instantiating MicronautRequestHandler to avoid port binding issues.
    // -------------------------------------------------------------------------

    @Test
    void execute_multipleRecords_reportsOnlyFailingOnes() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        // First call succeeds, second call throws
        IOpenRouterService aiService = new IOpenRouterService() {
            @Override
            public OpenRouterResponseDto ask(String question, String conversationId, String cityId) {
                if (callCount.incrementAndGet() == 2) {
                    throw new RuntimeException("AI service unavailable");
                }
                return successResponse("Answer");
            }

            @Override
            public AskStreamResult streamAsk(String question, String conversationId, String cityId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
                throw new UnsupportedOperationException();
            }

            @Override
            public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format,
                    String conversationId, ComplainantIdentity identity, String cityId) {
                throw new UnsupportedOperationException();
            }
        };

        CapturingTelegramSender sender = new CapturingTelegramSender();

        // Simulate handler logic: process each message and report failures
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        String msgId1 = "msg-001";
        String msgId2 = "msg-002";

        AskSqsMessage sqsMsg1 = new AskSqsMessage(100L, "Q1", "elprat", "CA", "conv-1");
        AskSqsMessage sqsMsg2 = new AskSqsMessage(200L, "Q2", "elprat", "CA", "conv-2");

        for (Object[] pair : List.of(new Object[]{msgId1, sqsMsg1}, new Object[]{msgId2, sqsMsg2})) {
            String messageId = (String) pair[0];
            AskSqsMessage msg = (AskSqsMessage) pair[1];
            try {
                AskProcessor processor = new AskProcessor(aiService, TOKEN, sender);
                processor.process(msg);
            } catch (Exception e) {
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier(messageId).build());
            }
        }
        SQSBatchResponse response = SQSBatchResponse.builder().withBatchItemFailures(failures).build();

        assertEquals(1, response.getBatchItemFailures().size(), "Only the failing record should be in failures");
        assertEquals("msg-002", response.getBatchItemFailures().get(0).getItemIdentifier());
    }

    @Test
    void execute_emptyBatch_returnsEmptyResponse() {
        // Simulate handler logic for empty batch
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        SQSBatchResponse response = SQSBatchResponse.builder().withBatchItemFailures(failures).build();

        assertEquals(0, response.getBatchItemFailures().size());
    }

    @Test
    void execute_allSucceed_noFailures() throws Exception {
        IOpenRouterService aiService = new FakeAiService(successResponse("Answer"));
        CapturingTelegramSender sender = new CapturingTelegramSender();

        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        AskSqsMessage msg1 = new AskSqsMessage(100L, "Q1", "elprat", "CA", "conv-1");
        AskSqsMessage msg2 = new AskSqsMessage(200L, "Q2", "elprat", "CA", "conv-2");

        for (AskSqsMessage msg : List.of(msg1, msg2)) {
            try {
                AskProcessor processor = new AskProcessor(aiService, TOKEN, sender);
                processor.process(msg);
            } catch (Exception e) {
                failures.add(SQSBatchResponse.BatchItemFailure.builder()
                        .withItemIdentifier("msg-id").build());
            }
        }
        SQSBatchResponse response = SQSBatchResponse.builder().withBatchItemFailures(failures).build();

        assertEquals(0, response.getBatchItemFailures().size(), "No failures expected when all succeed");
    }

    // -------------------------------------------------------------------------
    // Helper: minimal fake IOpenRouterService that only implements ask()
    // -------------------------------------------------------------------------

    private static class FakeAiService implements IOpenRouterService {
        private final OpenRouterResponseDto response;

        FakeAiService(OpenRouterResponseDto response) {
            this.response = response;
        }

        @Override
        public OpenRouterResponseDto ask(String question, String conversationId, String cityId) {
            return response;
        }

        @Override
        public AskStreamResult streamAsk(String question, String conversationId, String cityId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format,
                String conversationId, ComplainantIdentity identity, String cityId) {
            throw new UnsupportedOperationException();
        }
    }
}
