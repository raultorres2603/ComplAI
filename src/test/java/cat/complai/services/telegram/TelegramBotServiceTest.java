package cat.complai.services.telegram;

import cat.complai.config.TelegramConfiguration;
import cat.complai.controllers.telegram.dto.*;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.services.feedback.FeedbackPublisherService;
import cat.complai.services.openrouter.IOpenRouterService;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
import cat.complai.services.telegram.TelegramSessionStore.TelegramMode;
import cat.complai.utilities.s3.S3PdfUploader;
import cat.complai.utilities.sqs.SqsComplaintPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TelegramBotService}.
 *
 * <p>Uses Mockito to verify routing logic, command handling, and mode transitions
 * without requiring a real Telegram Bot API endpoint. HTTP calls to the Telegram
 * API (sendMessage, answerCallbackQuery, sendChatAction) are made by the real
 * {@code HttpClient} but silently fail because the mock token produces invalid
 * URLs — exceptions are caught and logged inside those private methods.
 */
@ExtendWith(MockitoExtension.class)
class TelegramBotServiceTest {

    @Mock
    private TelegramConfiguration telegramConfig;

    @Mock
    private TelegramSessionStore sessionStore;

    @Mock
    private IOpenRouterService openRouterService;

    @Mock
    private ConversationManagementService conversationService;

    @Mock
    private SqsComplaintPublisher sqsPublisher;

    @Mock
    private S3PdfUploader s3PdfUploader;

    @Mock
    private FeedbackPublisherService feedbackPublisher;

    @InjectMocks
    private TelegramBotService service;

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() {
        // All tests that pass a cityId will eventually trigger resolveToken(cityId)
        // which calls telegramConfig.getToken(). Use lenient since parseIdentity
        // tests never call getToken at all.
        lenient().when(telegramConfig.getToken(anyString())).thenReturn("mock-token");
    }

    /** Build a TelegramUpdate containing a text message. */
    private static TelegramUpdate textUpdate(long chatId, String text) {
        TelegramChat chat = new TelegramChat(chatId, "private", "Test");
        TelegramMessage message = new TelegramMessage(1, null, chat, 1000, text);
        return new TelegramUpdate(1, message, null);
    }

    /** Build a TelegramUpdate containing a callback query. */
    private static TelegramUpdate callbackUpdate(long chatId, String callbackId, String data) {
        TelegramUser user = new TelegramUser(chatId, false, "Test", null);
        TelegramCallbackQuery callback = new TelegramCallbackQuery(callbackId, user, null, data);
        return new TelegramUpdate(1, null, callback);
    }

    // -------------------------------------------------------------------------
    // Command tests
    // -------------------------------------------------------------------------

    @Test
    void processUpdate_withStartCommand_sendsWelcomeMessage() {
        long chatId = 100L;
        when(sessionStore.getLanguage(chatId)).thenReturn("CA");

        service.processUpdate(textUpdate(chatId, "/start"), "elprat");

        verify(sessionStore).clearSession(chatId);
        verify(sessionStore).getLanguage(chatId);
        verifyNoMoreInteractions(sessionStore);
    }

    @Test
    void processUpdate_withHelpCommand_sendsHelpMessage() {
        long chatId = 200L;
        when(sessionStore.getLanguage(chatId)).thenReturn("EN");

        service.processUpdate(textUpdate(chatId, "/help"), "testcity");

        verify(sessionStore).getLanguage(chatId);
    }

    // -------------------------------------------------------------------------
    // Ask-mode + text routing
    // -------------------------------------------------------------------------

    @Test
    void processUpdate_withAskModeAndText_callsOpenRouterService() {
        long chatId = 300L;
        String query = "Quins són els horaris de l'ajuntament?";
        String conversationId = "telegram:" + chatId;

        when(sessionStore.getLanguage(chatId)).thenReturn("CA");
        when(sessionStore.getMode(chatId)).thenReturn(TelegramMode.ASK);
        when(sessionStore.getOrCreateConversationId(chatId)).thenReturn(conversationId);

        OpenRouterResponseDto response = new OpenRouterResponseDto(
                true, "L'horari és de 9 a 14h.", null, 200, OpenRouterErrorCode.NONE);
        when(openRouterService.ask(query, conversationId, "testcity")).thenReturn(response);

        service.processUpdate(textUpdate(chatId, query), "testcity");

        verify(openRouterService).ask(query, conversationId, "testcity");
        verify(sessionStore).getOrCreateConversationId(chatId);
    }

    // -------------------------------------------------------------------------
    // Callback – mode selection
    // -------------------------------------------------------------------------

    @Test
    void processUpdate_withCallbackModeAsk_setsModeToAsk() {
        long chatId = 400L;
        when(sessionStore.getLanguage(chatId)).thenReturn("EN");

        service.processUpdate(callbackUpdate(chatId, "cb-ask", "mode_ask"), "testcity");

        verify(sessionStore).setMode(chatId, TelegramMode.ASK);
    }

    @Test
    void processUpdate_withCallbackModeRedact_setsModeToRedact() {
        long chatId = 500L;
        when(sessionStore.getLanguage(chatId)).thenReturn("EN");

        service.processUpdate(callbackUpdate(chatId, "cb-redact", "mode_redact"), "testcity");

        verify(sessionStore).setMode(chatId, TelegramMode.REDACT);
    }

    @Test
    void processUpdate_withCallbackModeFeedback_setsModeToFeedback() {
        long chatId = 600L;
        when(sessionStore.getLanguage(chatId)).thenReturn("EN");

        service.processUpdate(callbackUpdate(chatId, "cb-feedback", "mode_feedback"), "testcity");

        verify(sessionStore).setMode(chatId, TelegramMode.FEEDBACK);
    }

    // -------------------------------------------------------------------------
    // Callback – language selection
    // -------------------------------------------------------------------------

    @Test
    void processUpdate_withLanguageSelection_updatesLanguage() {
        long chatId = 700L;
        when(sessionStore.getLanguage(chatId)).thenReturn("EN");

        service.processUpdate(callbackUpdate(chatId, "cb-lang", "lang_ca"), "testcity");

        verify(sessionStore).setLanguage(chatId, "CA");
    }

    // -------------------------------------------------------------------------
    // Static parseIdentity
    // -------------------------------------------------------------------------

    @Test
    void parseIdentity_withThreeCommas_returnsCompleteIdentity() {
        ComplainantIdentity identity = TelegramBotService.parseIdentity("Juan, García Pérez, 12345678Z");

        assertEquals("Juan", identity.name());
        assertEquals("García Pérez", identity.surname());
        assertEquals("12345678Z", identity.idNumber());
        assertTrue(identity.isComplete());
    }

    @Test
    void parseIdentity_withTwoCommas_parsesCorrectly() {
        ComplainantIdentity identity = TelegramBotService.parseIdentity("Juan García Pérez, 12345678Z");

        assertEquals("Juan", identity.name());
        assertEquals("García Pérez", identity.surname());
        assertEquals("12345678Z", identity.idNumber());
        assertTrue(identity.isComplete());
    }

    @Test
    void parseIdentity_withNifExtraction_extractsNif() {
        ComplainantIdentity identity = TelegramBotService.parseIdentity("María López 12345678Z");

        assertNotNull(identity.idNumber());
        assertEquals("12345678Z", identity.idNumber());
        assertEquals("María", identity.name());
        assertEquals("López", identity.surname());
        assertTrue(identity.isComplete());
    }
}
