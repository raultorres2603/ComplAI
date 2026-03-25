package cat.complai.openrouter.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum OpenRouterErrorCode {
    NONE(0),
    VALIDATION(1),
    REFUSAL(2),
    UPSTREAM(3),
    TIMEOUT(4),
    INTERNAL(5),
    // Emitted by JwtAuthFilter before the controller is reached.
    // The controller switch does not need a case for this — the filter short-circuits the request.
    UNAUTHORIZED(6),
    // Emitted by RateLimitFilter when a user exceeds the per-minute request cap.
    RATE_LIMITED(7);

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
