package cat.complai.openrouter.services.conversation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Singleton
public class ConversationManagementService {
    
    private static final int MAX_HISTORY_TURNS = 10;
    private final Cache<String, List<MessageEntry>> conversationCache;
    private final Cache<String, String> pendingComplaintCache;
    
    public ConversationManagementService() {
        this.conversationCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
        this.pendingComplaintCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
    }
    
    public void updateConversationHistory(String conversationId, String userMessage, String assistantMessage) {
        if (conversationId == null || conversationId.isBlank() || assistantMessage == null) return;
        List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
        if (history == null) history = new ArrayList<>();
        if (userMessage != null && !userMessage.isBlank()) {
            history.add(new MessageEntry("user", userMessage.trim()));
        }
        history.add(new MessageEntry("assistant", assistantMessage));
        // Cap history
        if (history.size() > MAX_HISTORY_TURNS * 2) {
            history = history.subList(history.size() - MAX_HISTORY_TURNS * 2, history.size());
        }
        conversationCache.put(conversationId, history);
    }
    
    public List<MessageEntry> getConversationHistory(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
        return history != null ? history : List.of();
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
    
    public record MessageEntry(String role, String content) {}
}
