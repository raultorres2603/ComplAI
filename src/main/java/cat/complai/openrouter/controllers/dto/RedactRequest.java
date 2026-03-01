package cat.complai.openrouter.controllers.dto;

import io.micronaut.core.annotation.Introspected;
import cat.complai.openrouter.dto.OutputFormat;

@Introspected
public class RedactRequest {
    private final String text;
    private OutputFormat format = OutputFormat.AUTO;
    private final String conversationId;

    public RedactRequest(String text) {
        this(text, OutputFormat.AUTO, null);
    }

    public RedactRequest(String text, OutputFormat format) {
        this(text, format, null);
    }

    public RedactRequest(String text, OutputFormat format, String conversationId) {
        this.text = text;
        this.format = format;
        this.conversationId = conversationId;
    }

    public String getText() {
        return text;
    }

    public OutputFormat getFormat() {
        return format;
    }

    public String getConversationId() {
        return conversationId;
    }
}
