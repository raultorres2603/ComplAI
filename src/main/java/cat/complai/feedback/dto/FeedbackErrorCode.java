package cat.complai.feedback.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Typed error codes for the feedback endpoint.
 *
 * <p>Used in FeedbackResult to represent validation and operational failures
 * without broad exception-driven control flow.
 */
public enum FeedbackErrorCode {
    VALIDATION(100),
    QUEUE_PUBLISH_FAILED(101),
    INTERNAL(102);

    private final int code;

    FeedbackErrorCode(int code) {
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
