package cat.complai.openrouter.dto;

import org.reactivestreams.Publisher;

/**
 * Sealed interface representing the two possible outcomes of a streaming ask request.
 *
 * <p>Pattern-matched by {@link cat.complai.openrouter.controllers.OpenRouterController#ask}
 * to decide whether to start an SSE stream or return an error response immediately.
 *
 * <ul>
 *   <li>{@link Success} — the stream is ready; the controller wraps it in SSE events.</li>
 *   <li>{@link Error} — a pre-stream failure occurred; the controller maps it to an HTTP error.</li>
 * </ul>
 */
public sealed interface AskStreamResult {
    record Success(Publisher<String> stream) implements AskStreamResult {
    }

    record Error(OpenRouterResponseDto errorResponse) implements AskStreamResult {
    }
}