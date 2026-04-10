package cat.complai.openrouter.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enum of typed error codes returned by the ComplAI API.
 *
 * <p>The numeric {@code errorCode} field is included in every JSON response so front-end
 * clients can take programmatic action without parsing error message strings.
 *
 * <p>HTTP status mapping:
 * <ul>
 *   <li>{@link #NONE} → 200</li>
 *   <li>{@link #VALIDATION} → 400</li>
 *   <li>{@link #REFUSAL} → 422</li>
 *   <li>{@link #UPSTREAM} → 502</li>
 *   <li>{@link #TIMEOUT} → 504</li>
 *   <li>{@link #INTERNAL} → 500</li>
 *   <li>{@link #UNAUTHORIZED} → 401</li>
 *   <li>{@link #RATE_LIMITED} → 429</li>
 * </ul>
 */
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
