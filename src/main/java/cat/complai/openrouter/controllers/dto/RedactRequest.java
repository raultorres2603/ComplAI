package cat.complai.openrouter.controllers.dto;

import io.micronaut.core.annotation.Introspected;
import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OutputFormat;

/**
 * Inbound DTO for POST /complai/redact.
 *
 * Identity fields (requesterName, requesterSurname, requesterIdNumber) are optional at the HTTP
 * boundary. When all three are provided the service embeds them directly into the AI prompt.
 * When any of them is missing the AI is instructed to ask the user for the missing data before
 * drafting the letter, exploiting the multi-turn conversation flow.
 *
 * Anonymous requests are rejected outright at the service layer: the Ajuntament requires
 * identification on all formal complaints.
 */
@Introspected
public class RedactRequest {
    private final String text;
    private OutputFormat format = OutputFormat.AUTO;
    private final String conversationId;
    private final String requesterName;
    private final String requesterSurname;
    private final String requesterIdNumber;

    public RedactRequest(String text) {
        this(text, OutputFormat.AUTO, null, null, null, null);
    }

    public RedactRequest(String text, OutputFormat format) {
        this(text, format, null, null, null, null);
    }

    public RedactRequest(String text, OutputFormat format, String conversationId) {
        this(text, format, conversationId, null, null, null);
    }

    public RedactRequest(String text, OutputFormat format, String conversationId,
                         String requesterName, String requesterSurname, String requesterIdNumber) {
        this.text = text;
        this.format = format;
        this.conversationId = conversationId;
        this.requesterName = requesterName;
        this.requesterSurname = requesterSurname;
        this.requesterIdNumber = requesterIdNumber;
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

    public String getRequesterName() {
        return requesterName;
    }

    public String getRequesterSurname() {
        return requesterSurname;
    }

    public String getRequesterIdNumber() {
        return requesterIdNumber;
    }

    /**
     * Assembles the identity record from the individual fields.
     * Returns null only when all three fields are absent — callers must handle the missing-identity
     * case by instructing the AI to request the information from the user.
     */
    public ComplainantIdentity getComplainantIdentity() {
        if (requesterName == null && requesterSurname == null && requesterIdNumber == null) {
            return null;
        }
        return new ComplainantIdentity(requesterName, requesterSurname, requesterIdNumber);
    }
}
