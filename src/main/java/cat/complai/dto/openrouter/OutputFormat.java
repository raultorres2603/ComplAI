package cat.complai.dto.openrouter;

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
     * supplied by the client" from a legitimate AUTO/JSON/PDF. A null/absent value
     * maps to PDF (the only valid client-facing format).
     */
    @JsonCreator
    public static OutputFormat fromString(String value) {
        if (value == null) return PDF;
        return switch (value.trim().toLowerCase()) {
            case "pdf" -> PDF;
            case "json" -> JSON;
            case "auto" -> AUTO;
            default -> null; // unrecognised — caller must handle
        };
    }

    /**
     * Returns true for PDF and AUTO — the two formats a client may send.
     * {@code AUTO} is resolved to {@code PDF} at the HTTP boundary in the controller.
     * Used at the HTTP boundary to reject unsupported format strings early.
     */
    public static boolean isSupportedClientFormat(OutputFormat f) {
        return f == PDF || f == AUTO;
    }
}

