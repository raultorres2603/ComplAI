package cat.complai.dto.http;

import cat.complai.exceptions.OpenRouterStreamingException;
import org.reactivestreams.Publisher;

/**
 * Sealed result type for the outcome of initiating an OpenRouter streaming
 * request.
 *
 * <p>
 * Use pattern matching to distinguish a successfully started stream from an
 * immediate failure before any data is received.
 */
public sealed interface OpenRouterStreamStartResult {
    /**
     * The stream started successfully.
     *
     * @param stream the reactive publisher emitting raw SSE line strings
     */
    record Success(Publisher<String> stream) implements OpenRouterStreamStartResult {
    }

    /**
     * The request failed before any streaming data was received.
     *
     * @param failure the exception describing the failure reason and error code
     */
    record Error(OpenRouterStreamingException failure) implements OpenRouterStreamStartResult {
    }
}