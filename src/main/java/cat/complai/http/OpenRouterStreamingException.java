package cat.complai.http;

import cat.complai.openrouter.dto.OpenRouterErrorCode;

/**
 * Signals that an SSE streaming call to OpenRouter failed.
 *
 * <p>Carries a typed {@link OpenRouterErrorCode} and an optional upstream HTTP status code
 * so the controller can produce accurate error metrics and log entries without inspecting
 * exception message strings.
 *
 * <p>Thrown by {@link HttpWrapper} streaming operations and mapped to HTTP error responses
 * by {@link cat.complai.openrouter.helpers.AskStreamErrorMapper}.
 */
public final class OpenRouterStreamingException extends RuntimeException {
    private final OpenRouterErrorCode errorCode;
    private final Integer upstreamStatus;

    public OpenRouterStreamingException(OpenRouterErrorCode errorCode, String message, Integer upstreamStatus) {
        super(message);
        this.errorCode = errorCode;
        this.upstreamStatus = upstreamStatus;
    }

    public OpenRouterStreamingException(OpenRouterErrorCode errorCode, String message, Integer upstreamStatus,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.upstreamStatus = upstreamStatus;
    }

    public OpenRouterErrorCode getErrorCode() {
        return errorCode;
    }

    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }
}