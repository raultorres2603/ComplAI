package cat.complai.exceptions;

import cat.complai.dto.openrouter.OpenRouterErrorCode;

/**
 * Thrown when an error occurs while initiating or reading an OpenRouter
 * streaming response.
 *
 * <p>
 * Carries a typed {@link OpenRouterErrorCode} and, where available, the HTTP
 * status
 * returned by the upstream API so callers can distinguish rate-limit (429)
 * errors from
 * server-side failures (5xx).
 */
public final class OpenRouterStreamingException extends RuntimeException {
    private final OpenRouterErrorCode errorCode;
    private final Integer upstreamStatus;

    /**
     * Constructs the exception with an error classification, message, and upstream
     * HTTP status.
     *
     * @param errorCode      typed error classification
     * @param message        human-readable description of the failure
     * @param upstreamStatus HTTP status code returned by the upstream API, or
     *                       {@code null} if unavailable
     */
    public OpenRouterStreamingException(OpenRouterErrorCode errorCode, String message, Integer upstreamStatus) {
        super(message);
        this.errorCode = errorCode;
        this.upstreamStatus = upstreamStatus;
    }

    /**
     * Constructs the exception with an error classification, message, upstream HTTP
     * status, and cause.
     *
     * @param errorCode      typed error classification
     * @param message        human-readable description of the failure
     * @param upstreamStatus HTTP status code returned by the upstream API, or
     *                       {@code null} if unavailable
     * @param cause          the underlying exception
     */
    public OpenRouterStreamingException(OpenRouterErrorCode errorCode, String message, Integer upstreamStatus,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.upstreamStatus = upstreamStatus;
    }

    /**
     * Returns the typed error code classifying this failure.
     *
     * @return error code
     */
    public OpenRouterErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the HTTP status code returned by the upstream API, if available.
     *
     * @return upstream HTTP status code, or {@code null} if not applicable
     */
    public Integer getUpstreamStatus() {
        return upstreamStatus;
    }
}