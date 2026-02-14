package cat.complai.openrouter.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum OpenRouterErrorCode {
    NONE(0),
    VALIDATION(1),
    REFUSAL(2),
    UPSTREAM(3),
    TIMEOUT(4),
    INTERNAL(5);

    private final int code;

    OpenRouterErrorCode(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    @JsonValue
    public int toValue() {
        return code;
    }
}
