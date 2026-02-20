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

    @JsonCreator
    public static OutputFormat fromString(String value) {
        if (value == null) return AUTO;
        switch (value.trim().toLowerCase()) {
            case "pdf":
                return PDF;
            case "json":
                return JSON;
            case "auto":
                return AUTO;
            default:
                return AUTO; // safe default
        }
    }
}

