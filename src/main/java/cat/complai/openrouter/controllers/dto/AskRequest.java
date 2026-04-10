package cat.complai.openrouter.controllers.dto;

import io.micronaut.core.annotation.Introspected;

/**
 * Request DTO for the {@code POST /complai/ask} endpoint.
 */
@Introspected
public class AskRequest {
    private final String text;
    private final String conversationId;

    /**
     * Constructs a single-turn ask request with no conversation context.
     *
     * @param text the user's question text
     */
    public AskRequest(String text) {
        this(text, null);
    }

    /**
     * Constructs a multi-turn ask request with a conversation context key.
     *
     * @param text           the user's question text
     * @param conversationId optional conversation identifier to continue a prior
     *                       exchange
     */
    public AskRequest(String text, String conversationId) {
        this.text = text;
        this.conversationId = conversationId;
    }

    /**
     * Returns the user's question text.
     *
     * @return question text
     */
    public String getText() {
        return text;
    }

    /**
     * Returns the conversation identifier, or {@code null} for single-turn
     * requests.
     *
     * @return conversation ID, or {@code null}
     */
    public String getConversationId() {
        return conversationId;
    }
}
