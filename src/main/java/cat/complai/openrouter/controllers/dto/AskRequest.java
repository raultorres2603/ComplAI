package cat.complai.openrouter.controllers.dto;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class AskRequest {
    private final String text;
    private final String conversationId;

    public AskRequest(String text) {
        this(text, null);
    }

    public AskRequest(String text, String conversationId) {
        this.text = text;
        this.conversationId = conversationId;
    }

    public String getText() {
        return text;
    }

    public String getConversationId() {
        return conversationId;
    }
}
