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
 * Manages in-process conversation history using a Caffeine cache.
 *
 * <p>History is stored as an ordered list of {@link MessageEntry} records (role + content pairs)
 * keyed by conversation ID. Entries expire after 30 minutes of inactivity and the cache is
 * bounded to 10,000 concurrent conversations.
 *
 * <p>History depth is capped at {@code maxHistoryTurns} (default 5) full turns (i.e. 10
 * message entries) to control AI token costs. Older messages are pruned when the cap is
 * exceeded.
 *
 * <p>A secondary "pending complaint" cache holds the raw complaint text between the turn
 * where the user describes their problem and the turn where they provide identity details,
 * allowing the full context to be reconstructed for the final PDF prompt.
 */
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

    /**
     * Appends a user–assistant turn to the cached conversation history.
     *
     * <p>If the history would exceed {@code maxHistoryTurns} full turns the oldest messages
     * are pruned from the front. A {@code null} or blank {@code conversationId} is a no-op.
     *
     * @param conversationId  identifies the conversation; no-op if null or blank
     * @param userMessage     the citizen's message; skipped if null or blank
     * @param assistantMessage the AI's response; no-op if null
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
     * Retrieves the current conversation history for the given ID.
     *
     * @param conversationId the session identifier; returns an empty list if null, blank, or unknown
     * @return an unmodifiable list of message entries in chronological order
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
     * Persists the pending complaint text for a multi-turn redact session.
     *
     * @param conversationId the session identifier; no-op if null or blank
     * @param complaint      the raw complaint text entered by the citizen
     */
    public void storePendingComplaint(String conversationId, String complaint) {
        if (conversationId != null && !conversationId.isBlank() && complaint != null) {
            pendingComplaintCache.put(conversationId, complaint);
        }
    }

    /**
     * Retrieves the pending complaint text for the given conversation, if any.
     *
     * @param conversationId the session identifier
     * @return the pending complaint text, or {@code null} if not present or expired
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
     * @param conversationId the session identifier; no-op if null or blank
     */
    public void clearPendingComplaint(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            pendingComplaintCache.invalidate(conversationId);
        }
    }

    /**
     * Appends all entries from {@code history} to {@code messages} as OpenAI-compatible
     * {@code role}/{@code content} map entries.
     *
     * @param messages the target list to append to
     * @param history  the conversation history to append; no-op if null or empty
     */
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
