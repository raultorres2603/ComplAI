package cat.complai.helpers.openrouter;

import cat.complai.exceptions.OpenRouterStreamingException;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.sse.SseErrorEvent;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Maps streaming-pipeline errors to the appropriate response DTOs and SSE error
 * events.
 *
 * <p>
 * Unwraps cause chains and resolves a typed {@link OpenRouterErrorCode} so that
 * callers do not need to inspect raw exception types.
 */
public final class AskStreamErrorMapper {
    private AskStreamErrorMapper() {
    }

    /**
     * Converts a streaming error to an {@link OpenRouterResponseDto} suitable for
     * returning a non-streaming HTTP error response.
     *
     * @param error the throwable from the streaming pipeline
     * @return an error response DTO with an appropriate error code and public
     *         message
     */
    public static OpenRouterResponseDto toResponseDto(Throwable error) {
        Throwable resolved = unwrap(error);
        OpenRouterErrorCode errorCode = resolveErrorCode(resolved);
        Integer upstreamStatus = resolved instanceof OpenRouterStreamingException streamEx
                ? streamEx.getUpstreamStatus()
                : null;
        return new OpenRouterResponseDto(false, null, publicMessage(errorCode, resolved), upstreamStatus, errorCode);
    }

    /**
     * Converts a streaming error to a {@link SseErrorEvent} to emit on the SSE
     * channel.
     *
     * @param error the throwable from the streaming pipeline
     * @return an SSE error event with an appropriate error code and public message
     */
    /**
     * Converts a streaming error to a {@link SseErrorEvent} to emit on the SSE
     * channel.
     *
     * @param error the throwable from the streaming pipeline
     * @return an SSE error event with an appropriate error code and public message
     */
    public static SseErrorEvent toSseErrorEvent(Throwable error) {
        Throwable resolved = unwrap(error);
        OpenRouterErrorCode errorCode = resolveErrorCode(resolved);
        return new SseErrorEvent(publicMessage(errorCode, resolved), errorCode.getCode());
    }

    /**
     * Resolves the typed {@link OpenRouterErrorCode} for a given error after
     * unwrapping
     * cause chains.
     *
     * @param error the throwable to classify
     * @return the resolved error code
     */
    /**
     * Resolves the typed {@link OpenRouterErrorCode} for a given error after
     * unwrapping
     * cause chains.
     *
     * @param error the throwable to classify
     * @return the resolved error code
     */
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