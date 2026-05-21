package cat.complai.services.telegram;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.inject.Singleton;
import java.util.concurrent.TimeUnit;

@Singleton
public class TelegramSessionStore {

    public enum TelegramMode {
        ASK, REDACT, FEEDBACK, NONE
    }

    public record TelegramUserSession(
        TelegramMode mode,
        String language,
        String pendingComplaintText,
        String conversationId
    ) {
        public TelegramUserSession() {
            this(TelegramMode.NONE, "CA", null, null);
        }
    }

    private final Cache<Long, TelegramUserSession> sessionCache;

    public TelegramSessionStore() {
        this.sessionCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();
    }

    public TelegramUserSession getSession(long chatId) {
        return sessionCache.get(chatId, k -> new TelegramUserSession());
    }

    public void setMode(long chatId, TelegramMode mode) {
        TelegramUserSession session = getSession(chatId);
        sessionCache.put(chatId, new TelegramUserSession(
            mode, session.language(), session.pendingComplaintText(), session.conversationId()
        ));
    }

    public TelegramMode getMode(long chatId) {
        return getSession(chatId).mode();
    }

    public void setLanguage(long chatId, String language) {
        TelegramUserSession session = getSession(chatId);
        sessionCache.put(chatId, new TelegramUserSession(
            session.mode(), language, session.pendingComplaintText(), session.conversationId()
        ));
    }

    public String getLanguage(long chatId) {
        return getSession(chatId).language();
    }

    public void setPendingComplaintText(long chatId, String text) {
        TelegramUserSession session = getSession(chatId);
        sessionCache.put(chatId, new TelegramUserSession(
            session.mode(), session.language(), text, session.conversationId()
        ));
    }

    /** Returns and clears the pending complaint text. */
    public String getAndClearPendingComplaintText(long chatId) {
        TelegramUserSession session = getSession(chatId);
        String text = session.pendingComplaintText();
        sessionCache.put(chatId, new TelegramUserSession(
            session.mode(), session.language(), null, session.conversationId()
        ));
        return text;
    }

    public void setConversationId(long chatId, String conversationId) {
        TelegramUserSession session = getSession(chatId);
        sessionCache.put(chatId, new TelegramUserSession(
            session.mode(), session.language(), session.pendingComplaintText(), conversationId
        ));
    }

    public String getOrCreateConversationId(long chatId) {
        TelegramUserSession session = getSession(chatId);
        if (session.conversationId() == null) {
            String newId = "telegram:" + chatId;
            sessionCache.put(chatId, new TelegramUserSession(
                session.mode(), session.language(), session.pendingComplaintText(), newId
            ));
            return newId;
        }
        return session.conversationId();
    }

    public void clearSession(long chatId) {
        sessionCache.invalidate(chatId);
    }
}
