package cat.complai.openrouter.services.conversation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Singleton
public class ConversationManagementService {

    private final Logger logger = Logger.getLogger(ConversationManagementService.class.getName());
    private final int maxHistoryTurns;
    private final Cache<String, List<MessageEntry>> conversationCache;
    private final Cache<String, String> pendingComplaintCache;

    public ConversationManagementService(@Value("${conversation.max-history-turns:5}") int maxHistoryTurns) {
        this.maxHistoryTurns = maxHistoryTurns;
        this.conversationCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
        this.pendingComplaintCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
        logger.info(() -> "ConversationManagementService initialized with maxHistoryTurns=" + maxHistoryTurns);
    }

    public void updateConversationHistory(String conversationId, String userMessage, String assistantMessage) {
        if (conversationId == null || conversationId.isBlank() || assistantMessage == null)
            return;
        List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
        if (history == null)
            history = new ArrayList<>();
        if (userMessage != null && !userMessage.isBlank()) {
            history.add(new MessageEntry("user", userMessage.trim()));
        }
        history.add(new MessageEntry("assistant", assistantMessage));
        // Cap history at maxHistoryTurns * 2 (each turn = user + assistant message)
        final int maxTurns = maxHistoryTurns;
        if (history.size() > maxTurns * 2) {
            history = history.subList(history.size() - maxTurns * 2, history.size());
            logger.fine(() -> "updateConversationHistory() — conversationId=" + conversationId
                    + " pruned to maxHistoryTurns=" + maxTurns);
        }
        @SuppressWarnings("unchecked")
        List<MessageEntry> historyList = (List<MessageEntry>) (List<?>) history;
        storeCacheEntry(conversationId, historyList);
        final int currentHistorySize = history.size();
        logger.fine(() -> "updateConversationHistory() — conversationId=" + conversationId
                + " currentHistorySize=" + currentHistorySize + " maxHistoryTurns=" + maxTurns);
    }

    public List<MessageEntry> getConversationHistory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
        if (history != null) {
            final int maxTurns = maxHistoryTurns;
            logger.fine(() -> "getConversationHistory() — conversationId=" + conversationId
                    + " historySize=" + history.size() + " maxHistoryTurns=" + maxTurns);
        }
        return history != null ? history : List.of();
    }

    private void storeCacheEntry(String conversationId, List<MessageEntry> historyList) {
        conversationCache.put(conversationId, historyList);
    }

    public void storePendingComplaint(String conversationId, String complaint) {
        if (conversationId != null && !conversationId.isBlank() && complaint != null) {
            pendingComplaintCache.put(conversationId, complaint);
        }
    }

    public String getPendingComplaint(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return pendingComplaintCache.getIfPresent(conversationId);
    }

    public void clearPendingComplaint(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            pendingComplaintCache.invalidate(conversationId);
        }
    }

    public void addToMessages(List<Map<String, Object>> messages, List<MessageEntry> history) {
        if (history != null) {
            for (MessageEntry entry : history) {
                messages.add(Map.of("role", entry.role(), "content", entry.content()));
            }
        }
    }

    public record MessageEntry(String role, String content) {
    }
}
