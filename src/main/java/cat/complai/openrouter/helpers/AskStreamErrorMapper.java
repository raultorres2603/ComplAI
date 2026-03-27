package cat.complai.openrouter.helpers;

import cat.complai.http.OpenRouterStreamingException;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.sse.SseErrorEvent;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

public final class AskStreamErrorMapper {
    private AskStreamErrorMapper() {
    }

    public static OpenRouterResponseDto toResponseDto(Throwable error) {
        Throwable resolved = unwrap(error);
        OpenRouterErrorCode errorCode = resolveErrorCode(resolved);
        Integer upstreamStatus = resolved instanceof OpenRouterStreamingException streamEx
                ? streamEx.getUpstreamStatus()
                : null;
        return new OpenRouterResponseDto(false, null, publicMessage(errorCode, resolved), upstreamStatus, errorCode);
    }

    public static SseErrorEvent toSseErrorEvent(Throwable error) {
        Throwable resolved = unwrap(error);
        OpenRouterErrorCode errorCode = resolveErrorCode(resolved);
        return new SseErrorEvent(publicMessage(errorCode, resolved), errorCode.getCode());
    }

    public static OpenRouterErrorCode resolveErrorCode(Throwable error) {
        Throwable resolved = unwrap(error);
        if (resolved instanceof OpenRouterStreamingException streamEx) {
            return streamEx.getErrorCode();
        }
        if (resolved instanceof TimeoutException || resolved instanceof HttpTimeoutException) {
            return OpenRouterErrorCode.TIMEOUT;
        }

        String message = resolved.getMessage();
        if (message == null) {
            return OpenRouterErrorCode.INTERNAL;
        }

        String normalized = message.toLowerCase();
        if (normalized.contains("validation")) {
            return OpenRouterErrorCode.VALIDATION;
        }
        if (normalized.contains("malformed upstream stream")) {
            return OpenRouterErrorCode.UPSTREAM;
        }
        if (normalized.contains("openrouter") || normalized.contains("upstream")) {
            return OpenRouterErrorCode.UPSTREAM;
        }
        return OpenRouterErrorCode.INTERNAL;
    }

    private static String publicMessage(OpenRouterErrorCode errorCode, Throwable error) {
        return switch (errorCode) {
            case VALIDATION -> error != null && error.getMessage() != null ? error.getMessage() : "Invalid input.";
            case TIMEOUT -> "AI service timed out.";
            case UPSTREAM -> "AI service is temporarily unavailable. Please try again later.";
            default -> "An internal error occurred. Please try again later.";
        };
    }

    private static Throwable unwrap(Throwable error) {
        if (error == null) {
            return new IllegalStateException("Unknown error");
        }
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            if (current instanceof OpenRouterStreamingException) {
                return current;
            }
            current = current.getCause();
        }
        return current;
    }
}