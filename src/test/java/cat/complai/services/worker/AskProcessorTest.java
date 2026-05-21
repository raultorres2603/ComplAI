package cat.complai.services.worker;

import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.sqs.AskSqsMessage;
import cat.complai.services.openrouter.IOpenRouterService;
import cat.complai.services.worker.AskProcessor.TelegramSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AskProcessor}.
 *
 * <p>Tests the processor logic for calling the AI service and sending the answer
 * back via Telegram, using a capturing sender to avoid real HTTP calls.
 */
@ExtendWith(MockitoExtension.class)
class AskProcessorTest {

    @Mock
    private IOpenRouterService openRouterService;

    private static final long CHAT_ID = 12345L;
    private static final String CITY_ID = "elprat";
    private static final String LANG = "CA";
    private static final String QUESTION = "Quins són els horaris de l'ajuntament?";
    private static final String CONVERSATION_ID = "telegram-12345";
    private static final String TOKEN = "test-bot-token";

    /**
     * Captures send method calls for verification.
     */
    static class CapturingTelegramSender implements TelegramSender {
        final AtomicReference<String> capturedText = new AtomicReference<>();
        final AtomicReference<Long> capturedChatId = new AtomicReference<>();
        final AtomicReference<String> capturedToken = new AtomicReference<>();

        @Override
        public void sendMessage(long chatId, String text, String token) {
            capturedChatId.set(chatId);
            capturedText.set(text);
            capturedToken.set(token);
        }
    }

    private AskSqsMessage message() {
        return new AskSqsMessage(CHAT_ID, QUESTION, CITY_ID, LANG, CONVERSATION_ID);
    }

    private static OpenRouterResponseDto successResponse(String message) {
        return new OpenRouterResponseDto(true, message, null, 200, OpenRouterErrorCode.NONE);
    }

    @Test
    void process_successfulFlow_sendsAnswerToTelegram() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        when(openRouterService.ask(QUESTION, CONVERSATION_ID, CITY_ID))
                .thenReturn(successResponse("L'horari és de 9 a 14h."));

        processor.process(message());

        verify(openRouterService).ask(QUESTION, CONVERSATION_ID, CITY_ID);
        assertEquals(CHAT_ID, sender.capturedChatId.get());
        assertEquals("L'horari és de 9 a 14h.", sender.capturedText.get());
        assertEquals(TOKEN, sender.capturedToken.get());
    }

    @Test
    void process_nullResponse_sendsFallbackError() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        when(openRouterService.ask(anyString(), anyString(), anyString())).thenReturn(null);

        processor.process(message());

        String reply = sender.capturedText.get();
        assertNotNull(reply);
        assertTrue(reply.contains("No he pogut"), "Should contain Catalan fallback error");
    }

    @Test
    void process_nullMessageInResponse_sendsFallbackError() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        when(openRouterService.ask(anyString(), anyString(), anyString()))
                .thenReturn(new OpenRouterResponseDto(false, null, "Some error", 500, OpenRouterErrorCode.UPSTREAM));

        processor.process(message());

        String reply = sender.capturedText.get();
        assertNotNull(reply);
        assertTrue(reply.contains("No he pogut"), "Should contain Catalan fallback error");
    }

    @Test
    void process_blankMessageInResponse_sendsFallbackError() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        when(openRouterService.ask(anyString(), anyString(), anyString()))
                .thenReturn(successResponse("   "));

        processor.process(message());

        String reply = sender.capturedText.get();
        assertNotNull(reply);
        assertTrue(reply.contains("No he pogut"), "Should contain Catalan fallback error for blank message");
    }

    @Test
    void process_spanishLanguage_sendsSpanishFallback() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        when(openRouterService.ask(anyString(), anyString(), anyString())).thenReturn(null);

        AskSqsMessage msg = new AskSqsMessage(CHAT_ID, QUESTION, CITY_ID, "ES", CONVERSATION_ID);
        processor.process(msg);

        String reply = sender.capturedText.get();
        assertNotNull(reply);
        assertTrue(reply.contains("No he podido"), "Should contain Spanish fallback error");
    }

    @Test
    void process_frenchLanguage_sendsFrenchFallback() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        when(openRouterService.ask(anyString(), anyString(), anyString())).thenReturn(null);

        AskSqsMessage msg = new AskSqsMessage(CHAT_ID, QUESTION, CITY_ID, "FR", CONVERSATION_ID);
        processor.process(msg);

        String reply = sender.capturedText.get();
        assertNotNull(reply);
        assertTrue(reply.contains("n\u2019ai pas pu"), "Should contain French fallback error");
    }

    @Test
    void process_englishLanguage_sendsEnglishFallback() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        when(openRouterService.ask(anyString(), anyString(), anyString())).thenReturn(null);

        AskSqsMessage msg = new AskSqsMessage(CHAT_ID, QUESTION, CITY_ID, "EN", CONVERSATION_ID);
        processor.process(msg);

        String reply = sender.capturedText.get();
        assertNotNull(reply);
        assertTrue(reply.contains("could not process"), "Should contain English fallback error");
    }

    @Test
    void process_nullCityId_throwsIllegalArgumentException() {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        AskSqsMessage msg = new AskSqsMessage(CHAT_ID, QUESTION, null, LANG, CONVERSATION_ID);

        assertThrows(IllegalArgumentException.class, () -> processor.process(msg));
        verifyNoInteractions(openRouterService);
    }

    @Test
    void process_blankCityId_throwsIllegalArgumentException() {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        AskSqsMessage msg = new AskSqsMessage(CHAT_ID, QUESTION, "   ", LANG, CONVERSATION_ID);

        assertThrows(IllegalArgumentException.class, () -> processor.process(msg));
        verifyNoInteractions(openRouterService);
    }

    @Test
    void process_nullToken_throwsIllegalStateException() {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, null, sender);

        assertThrows(IllegalStateException.class, () -> processor.process(message()));
        verifyNoInteractions(openRouterService);
    }

    @Test
    void process_blankToken_throwsIllegalStateException() {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, "   ", sender);

        assertThrows(IllegalStateException.class, () -> processor.process(message()));
        verifyNoInteractions(openRouterService);
    }

    @Test
    void process_nullConversationId_generatesDefault() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        when(openRouterService.ask(eq(QUESTION), eq("telegram-" + CHAT_ID), eq(CITY_ID)))
                .thenReturn(successResponse("Answer"));

        AskSqsMessage msg = new AskSqsMessage(CHAT_ID, QUESTION, CITY_ID, LANG, null);
        processor.process(msg);

        verify(openRouterService).ask(QUESTION, "telegram-" + CHAT_ID, CITY_ID);
    }

    @Test
    void process_blankConversationId_generatesDefault() throws Exception {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        when(openRouterService.ask(eq(QUESTION), eq("telegram-" + CHAT_ID), eq(CITY_ID)))
                .thenReturn(successResponse("Answer"));

        AskSqsMessage msg = new AskSqsMessage(CHAT_ID, QUESTION, CITY_ID, LANG, "   ");
        processor.process(msg);

        verify(openRouterService).ask(QUESTION, "telegram-" + CHAT_ID, CITY_ID);
    }

    @Test
    void process_telegramSenderException_propagates() throws Exception {
        TelegramSender failingSender = (chatId, text, token) -> {
            throw new RuntimeException("Telegram API error");
        };
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, failingSender);

        when(openRouterService.ask(anyString(), anyString(), anyString()))
                .thenReturn(successResponse("Answer"));

        assertThrows(RuntimeException.class, () -> processor.process(message()),
                "Telegram sender exceptions must propagate for DLQ routing");
    }

    @Test
    void buildReply_withValidMessage_returnsMessage() {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        OpenRouterResponseDto response = successResponse("Hello world");
        String reply = processor.buildReply(response, "CA");

        assertEquals("Hello world", reply);
    }

    @Test
    void buildReply_withNullResponse_returnsCatalanFallback() {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        String reply = processor.buildReply(null, "CA");

        assertNotNull(reply);
        assertTrue(reply.contains("No he pogut"));
    }

    @Test
    void buildReply_withNullLang_defaultsToCatalan() {
        CapturingTelegramSender sender = new CapturingTelegramSender();
        AskProcessor processor = new AskProcessor(openRouterService, TOKEN, sender);

        String reply = processor.buildReply(null, null);

        assertNotNull(reply);
        assertTrue(reply.contains("No he pogut"));
    }
}
