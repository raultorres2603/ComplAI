package cat.complai.openrouter.controllers.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private final OutputFormat format;
    private final String conversationId;
    private final String requesterName;
    private final String requesterSurname;
    private final String requesterIdNumber;

    public RedactRequest(String text) {
        this(text, OutputFormat.PDF, null, null, null, null);
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

    /**
     * Jackson deserialization entry point. The format field is received as a raw String so
     * that OutputFormat.fromString can return null for unrecognised values (e.g. "xml"), which
     * the controller then rejects with a 400. A null/absent format field maps to PDF.
     */
    @JsonCreator
    public static RedactRequest fromJson(
            @JsonProperty("text") String text,
            @JsonProperty("format") String format,
            @JsonProperty("conversationId") String conversationId,
            @JsonProperty("requesterName") String requesterName,
            @JsonProperty("requesterSurname") String requesterSurname,
            @JsonProperty("requesterIdNumber") String requesterIdNumber) {
        // OutputFormat.fromString returns null for unrecognised values (e.g. "xml") and AUTO
        // for null (absent field). Passing null through to the constructor preserves the
        // "unknown format" sentinel so isSupportedClientFormat in the controller can reject it.
        return new RedactRequest(text, OutputFormat.fromString(format), conversationId,
                requesterName, requesterSurname, requesterIdNumber);
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
     * Blank or whitespace-only strings are treated as absent (normalized to null) so that
     * the rest of the system never has to defend against blank field values.
     * Returns null when all three fields are absent or blank.
     */
    public ComplainantIdentity getComplainantIdentity() {
        String normalizedName     = normalize(requesterName);
        String normalizedSurname  = normalize(requesterSurname);
        String normalizedId       = normalize(requesterIdNumber);

        if (normalizedName == null && normalizedSurname == null && normalizedId == null) {
            return null;
        }
        return new ComplainantIdentity(normalizedName, normalizedSurname, normalizedId);
    }

    /**
     * Returns the trimmed value, or null if the value is null or blank.
     * Centralises the blank-to-null normalisation for all identity fields.
     */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
