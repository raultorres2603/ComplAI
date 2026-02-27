package cat.complai.openrouter.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum OutputFormat {
    JSON,
    PDF,
    AUTO; // let the AI decide

    @JsonValue
    public String toValue() {
        return this.name().toLowerCase();
    }

    /**
     * Returns null for unrecognised values so callers can distinguish "unknown format
     * supplied by the client" from a legitimate AUTO/JSON/PDF. Callers that need a
     * safe default (e.g. internal logic with no user input) should null-check the
     * result and fall back to AUTO explicitly.
     */
    @JsonCreator
    public static OutputFormat fromString(String value) {
        if (value == null) return AUTO;
        return switch (value.trim().toLowerCase()) {
            case "pdf" -> PDF;
            case "json" -> JSON;
            case "auto" -> AUTO;
            default -> null; // unrecognised — caller must handle
        };
    }

    /**
     * Returns true for the three values a client is allowed to send.
     * Used at the HTTP boundary to reject unsupported format strings early.
     */
    public static boolean isSupportedClientFormat(OutputFormat f) {
        return f == JSON || f == PDF || f == AUTO;
    }
}

