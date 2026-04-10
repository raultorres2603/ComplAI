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

/**
 * Manages conversation history and pending-complaint state for multi-turn
 * interactions.
 *
 * <p>
 * Conversation history is stored in a Caffeine in-memory cache keyed by
 * conversation ID,
 * with a 30-minute TTL and a maximum of 10 000 entries. Each turn consists of a
 * user message
 * and an assistant reply; the cache is capped at {@code maxHistoryTurns * 2}
 * messages per
 * conversation to bound memory usage.
 *
 * <p>
 * Pending complaints are stored separately for the async redact flow: when the
 * user
 * initiates a complaint but identity is incomplete, the original complaint text
 * is parked
 * here until the follow-up turn provides the missing fields.
 */
@Singleton
public class ConversationManagementService {

    private final Logger logger = Logger.getLogger(ConversationManagementService.class.getName());
    private final int maxHistoryTurns;
    private final Cache<String, List<MessageEntry>> conversationCache;
    private final Cache<String, String> pendingComplaintCache;

    /**
     * Constructs the service with a configurable maximum conversation history
     * depth.
     *
     * @param maxHistoryTurns maximum number of conversation turns to retain per
     *                        conversation;
     *                        defaults to 5
     */
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

    /**
     * Appends a user/assistant turn to the conversation history for the given ID.
     *
     * <p>
     * If the history exceeds {@code maxHistoryTurns * 2} messages the oldest turns
     * are
     * pruned. A blank or null {@code conversationId} is silently ignored.
     *
     * @param conversationId   the conversation key
     * @param userMessage      the user's message text (may be {@code null} or blank
     *                         to skip)
     * @param assistantMessage the assistant's reply text
     */
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

    /**
     * Returns the conversation history for the given ID as an unmodifiable list.
     *
     * @param conversationId the conversation key
     * @return list of message entries in chronological order; empty if no history
     *         exists
     */
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

    @SuppressWarnings("null")
    private void storeCacheEntry(String conversationId, List<MessageEntry> historyList) {
        conversationCache.put(conversationId, historyList);
    }

    /**
     * Stores a pending complaint text for the given conversation, to be retrieved
     * after
     * the user supplies missing identity fields.
     *
     * @param conversationId the conversation key
     * @param complaint      the complaint text to park
     */
    public void storePendingComplaint(String conversationId, String complaint) {
        if (conversationId != null && !conversationId.isBlank() && complaint != null) {
            pendingComplaintCache.put(conversationId, complaint);
        }
    }

    /**
     * Returns the pending complaint text for the given conversation, or
     * {@code null} if none.
     *
     * @param conversationId the conversation key
     * @return parked complaint text, or {@code null}
     */
    public String getPendingComplaint(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return pendingComplaintCache.getIfPresent(conversationId);
    }

    /**
     * Removes the pending complaint entry for the given conversation.
     *
     * @param conversationId the conversation key
     */
    public void clearPendingComplaint(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            pendingComplaintCache.invalidate(conversationId);
        }
    }

    /**
     * Appends each entry from {@code history} as a role/content map to
     * {@code messages}.
     *
     * @param messages the message list to extend
     * @param history  the conversation history entries to append
     */
    public void addToMessages(List<Map<String, Object>> messages, List<MessageEntry> history) {
        if (history != null) {
            for (MessageEntry entry : history) {
                messages.add(Map.of("role", entry.role(), "content", entry.content()));
            }
        }
    }

    /**
     * A single turn message in a conversation history.
     *
     * @param role    the speaker role ("user" or "assistant")
     * @param content the message text
     */
    public record MessageEntry(String role, String content) {
    }
}
