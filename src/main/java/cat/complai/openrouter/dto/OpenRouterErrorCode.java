package cat.complai.openrouter.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Typed error codes used in {@link OpenRouterResponseDto} to classify failures.
 *
 * <p>
 * The numeric code is exposed to external clients via {@link #toValue()} so
 * they can
 * switch on a stable integer rather than a string. The value is serialized
 * directly by
 * Jackson using {@link com.fasterxml.jackson.annotation.JsonValue}.
 */
public enum OpenRouterErrorCode {
    NONE(0),
    VALIDATION(1),
    REFUSAL(2),
    UPSTREAM(3),
    TIMEOUT(4),
    INTERNAL(5),
    // Emitted by JwtAuthFilter before the controller is reached.
    // The controller switch does not need a case for this — the filter
    // short-circuits the request.
    UNAUTHORIZED(6),
    // Emitted by RateLimitFilter when a user exceeds the per-minute request cap.
    RATE_LIMITED(7);

    private final int code;

    /**
     * Constructs the enum constant with its numeric code.
     *
     * @param code the numeric code exposed to clients
     */
    OpenRouterErrorCode(int code) {
        this.code = code;
    }

    /**
     * Returns the numeric error code.
     *
     * @return numeric code
     */
    public int getCode() {
        return code;
    }

    /**
     * Returns the numeric code for Jackson JSON serialization.
     *
     * @return numeric code
     */
    @JsonValue
    public int toValue() {
        return code;
    }
}
