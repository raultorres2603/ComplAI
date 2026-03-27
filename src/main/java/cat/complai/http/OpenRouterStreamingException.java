package cat.complai.http;

import cat.complai.openrouter.dto.OpenRouterErrorCode;

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