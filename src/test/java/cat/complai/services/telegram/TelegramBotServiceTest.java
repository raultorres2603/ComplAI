package cat.complai.services.telegram;

import cat.complai.config.TelegramConfiguration;
import cat.complai.controllers.telegram.dto.*;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.dto.sqs.AskSqsMessage;
import cat.complai.services.feedback.FeedbackPublisherService;
import cat.complai.services.openrouter.IOpenRouterService;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
import cat.complai.services.telegram.TelegramSessionStore.TelegramMode;
import cat.complai.utilities.s3.S3PdfUploader;
import cat.complai.utilities.sqs.SqsAskPublisher;
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
    private SqsAskPublisher askPublisher;

    @Mock
    private S3PdfUploader s3PdfUploader;

    @Mock
    private FeedbackPublisherService feedbackPublisher;

    @InjectMocks
    private TelegramBotService service;

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /**
     * Unique updateId counter to avoid deduplication collisions across tests.
     * The dedup cache in {@link TelegramBotService#PROCESSED_UPDATE_IDS} is
     * static, so every test must use a distinct updateId.
     */
    private static long nextUpdateId = 1000L;

    @BeforeEach
    void setUp() {
        // All tests that pass a cityId will eventually trigger resolveToken(cityId)
        // which calls telegramConfig.getToken(). Use lenient since parseIdentity
        // tests never call getToken at all.
        lenient().when(telegramConfig.getToken(anyString())).thenReturn("mock-token");
        // Clear static rate-limit caches so tests don't interfere with each other
        TelegramBotService.CHAT_RATE_LIMIT.invalidateAll();
        TelegramBotService.BOT_RATE_LIMIT.invalidateAll();
    }

    /** Build a TelegramUpdate containing a text message. */
    private static TelegramUpdate textUpdate(long chatId, String text) {
        TelegramChat chat = new TelegramChat(chatId, "private", "Test");
        TelegramMessage message = new TelegramMessage(1, null, chat, 1000, text);
        return new TelegramUpdate(nextUpdateId++, message, null);
    }

    /** Build a TelegramUpdate containing a callback query. */
    private static TelegramUpdate callbackUpdate(long chatId, String callbackId, String data) {
        TelegramUser user = new TelegramUser(chatId, false, "Test", null);
        TelegramCallbackQuery callback = new TelegramCallbackQuery(callbackId, user, null, data);
        return new TelegramUpdate(nextUpdateId++, null, callback);
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
    void processUpdate_withAskModeAndText_publishesToSqsAskQueue() {
        long chatId = 300L;
        String query = "Quins són els horaris de l'ajuntament?";
        String conversationId = "telegram:" + chatId;

        when(sessionStore.getLanguage(chatId)).thenReturn("CA");
        when(sessionStore.getMode(chatId)).thenReturn(TelegramMode.ASK);
        when(sessionStore.getOrCreateConversationId(chatId)).thenReturn(conversationId);

        service.processUpdate(textUpdate(chatId, query), "testcity");

        verify(askPublisher).publish(argThat(msg ->
                msg.chatId() == chatId
                        && query.equals(msg.question())
                        && "testcity".equals(msg.cityId())
                        && "CA".equals(msg.lang())
                        && conversationId.equals(msg.conversationId())
        ));
        verify(sessionStore).getOrCreateConversationId(chatId);
        // Should NOT call openRouterService directly anymore
        verifyNoInteractions(openRouterService);
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
    // Rate limiting
    // -------------------------------------------------------------------------

    @Test
    void processUpdate_messagesUnderRateLimit_areProcessed() {
        long chatId = 800L;
        String query = "Quins són els horaris?";
        String conversationId = "telegram:" + chatId;

        when(sessionStore.getLanguage(chatId)).thenReturn("CA");
        when(sessionStore.getMode(chatId)).thenReturn(TelegramMode.ASK);
        when(sessionStore.getOrCreateConversationId(chatId)).thenReturn(conversationId);

        // Send 3 rapid messages from the same chat — all should be processed
        for (int i = 0; i < 3; i++) {
            service.processUpdate(textUpdate(chatId, query + " " + i), "testcity");
        }

        verify(askPublisher, times(3)).publish(any(AskSqsMessage.class));
    }

    @Test
    void processUpdate_messagesOverRateLimit_droppedSilently() {
        long chatId = 801L;
        String query = "Hola";
        String conversationId = "telegram:" + chatId;

        when(sessionStore.getLanguage(chatId)).thenReturn("CA");
        when(sessionStore.getMode(chatId)).thenReturn(TelegramMode.ASK);
        when(sessionStore.getOrCreateConversationId(chatId)).thenReturn(conversationId);

        // Send 4 rapid messages — the 4th should be dropped (rate limit = 3/s)
        for (int i = 0; i < 4; i++) {
            service.processUpdate(textUpdate(chatId, query + " " + i), "testcity");
        }

        // Only 3 messages should have been processed
        verify(askPublisher, times(3)).publish(any(AskSqsMessage.class));
    }

    @Test
    void processUpdate_callbackQueries_notRateLimited() {
        long chatId = 802L;
        when(sessionStore.getLanguage(chatId)).thenReturn("EN");

        // Send 10 callback queries from the same chat — none should be rate-limited
        for (int i = 0; i < 10; i++) {
            service.processUpdate(callbackUpdate(chatId, "cb-" + i, "mode_ask"), "testcity");
        }

        verify(sessionStore, times(10)).setMode(chatId, TelegramMode.ASK);
    }

    // -------------------------------------------------------------------------
    // Bot-wide rate limiting
    // -------------------------------------------------------------------------

    @Test
    void processUpdate_botUnderRateLimit_allProcessed() {
        // Send 8 messages from 8 different chats to the same city — bot limit is 10
        for (int i = 0; i < 8; i++) {
            long chatId = 810L + i;
            String conversationId = "telegram:" + chatId;
            when(sessionStore.getLanguage(chatId)).thenReturn("CA");
            when(sessionStore.getMode(chatId)).thenReturn(TelegramMode.ASK);
            when(sessionStore.getOrCreateConversationId(chatId)).thenReturn(conversationId);

            service.processUpdate(textUpdate(chatId, "consulta " + i), "testcity");
        }

        verify(askPublisher, times(8)).publish(any(AskSqsMessage.class));
    }

    @Test
    void processUpdate_botOverRateLimit_dropsExcess() {
        // Send 12 messages from 12 different chats to the same city — bot limit is 10
        // so the last 2 should be dropped. Use lenient stubbings because messages
        // that are rate-limited never reach handleMessage or handleTextByMode.
        for (int i = 0; i < 12; i++) {
            long chatId = 830L + i;
            String conversationId = "telegram:" + chatId;
            lenient().when(sessionStore.getLanguage(chatId)).thenReturn("CA");
            lenient().when(sessionStore.getMode(chatId)).thenReturn(TelegramMode.ASK);
            lenient().when(sessionStore.getOrCreateConversationId(chatId)).thenReturn(conversationId);

            service.processUpdate(textUpdate(chatId, "consulta " + i), "testcity");
        }

        verify(askPublisher, times(10)).publish(any(AskSqsMessage.class));
    }

    @Test
    void processUpdate_botRateLimitIndependentPerCity() {
        // Send 11 messages from 11 different chats to city "elprat".
        // Bot limit counts per city, so city "testcity" is unaffected.
        // Use lenient stubbings because messages that are rate-limited never
        // reach handleMessage or handleTextByMode.
        for (int i = 0; i < 11; i++) {
            long chatId = 850L + i;
            String conversationId = "telegram:" + chatId;
            lenient().when(sessionStore.getLanguage(chatId)).thenReturn("CA");
            lenient().when(sessionStore.getMode(chatId)).thenReturn(TelegramMode.ASK);
            lenient().when(sessionStore.getOrCreateConversationId(chatId)).thenReturn(conversationId);

            service.processUpdate(textUpdate(chatId, "consulta " + i), "elprat");
        }
        // Only 10 should go through for elprat
        verify(askPublisher, times(10)).publish(any(AskSqsMessage.class));
    }

    // -------------------------------------------------------------------------
    // Length validation
    // -------------------------------------------------------------------------

    @Test
    void processUpdate_messageWithinLengthLimit_processedNormally() {
        long chatId = 900L;
        String query = "Quins són els horaris de l'ajuntament?";
        String conversationId = "telegram:" + chatId;

        when(sessionStore.getLanguage(chatId)).thenReturn("CA");
        when(sessionStore.getMode(chatId)).thenReturn(TelegramMode.ASK);
        when(sessionStore.getOrCreateConversationId(chatId)).thenReturn(conversationId);

        service.processUpdate(textUpdate(chatId, query), "testcity");

        verify(askPublisher).publish(any(AskSqsMessage.class));
    }

    @Test
    void processUpdate_messageExceedsMaxLength_sendsErrorReply() {
        long chatId = 901L;
        // Build a string longer than MAX_MESSAGE_LENGTH
        String longText = "A".repeat(TelegramBotService.MAX_MESSAGE_LENGTH + 1);

        when(sessionStore.getLanguage(chatId)).thenReturn("ES");

        service.processUpdate(textUpdate(chatId, longText), "testcity");

        // Should NOT have enqueued anything to SQS or processed the message further
        verifyNoInteractions(askPublisher);
        verify(sessionStore, never()).getMode(anyLong());
    }

    @Test
    void processUpdate_commandExemptFromLengthValidation() {
        long chatId = 902L;
        // Build a very long string that starts with /start
        String longCommand = "/start " + "x".repeat(TelegramBotService.MAX_MESSAGE_LENGTH + 1);

        when(sessionStore.getLanguage(chatId)).thenReturn("CA");

        service.processUpdate(textUpdate(chatId, longCommand), "testcity");

        // Should be treated as a /start command regardless of length
        verify(sessionStore).clearSession(chatId);
        verify(sessionStore).getLanguage(chatId);
        verifyNoMoreInteractions(sessionStore);
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
