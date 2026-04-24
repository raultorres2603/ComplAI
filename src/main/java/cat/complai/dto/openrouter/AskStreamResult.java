package cat.complai.dto.openrouter;

import org.reactivestreams.Publisher;

/**
 * Sealed result type for the outcome of initiating a streaming /ask response.
 *
 * <p>
 * Use pattern matching to distinguish a successfully started stream from an
 * error
 * captured before any data was emitted.
 */
public sealed interface AskStreamResult {
    /**
     * The stream started successfully.
     *
     * @param stream the reactive publisher emitting raw SSE JSON strings
     */
    record Success(Publisher<String> stream) implements AskStreamResult {
    }

    /**
     * The request failed before any streaming data was produced.
     *
     * @param errorResponse the error response DTO describing the failure
     */
    record Error(OpenRouterResponseDto errorResponse) implements AskStreamResult {
    }
}