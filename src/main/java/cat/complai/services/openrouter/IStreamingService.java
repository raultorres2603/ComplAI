package cat.complai.services.openrouter;

import cat.complai.dto.openrouter.AskStreamResult;

/**
 * Role interface for SSE streaming AI responses.
 *
 * <p>Part of the Interface Segregation refactoring of {@link IOpenRouterService}.
 * Clients that only need streaming question answering (e.g. the
 * {@code OpenRouterController} {@code /complai/ask} endpoint) should depend
 * on this interface rather than the composite {@link IOpenRouterService}.
 */
public interface IStreamingService {
    /**
     * Streams an answer to {@code question} as raw text deltas via SSE.
     * After the stream completes, the assembled response is saved to conversation
     * history.
     */
    AskStreamResult streamAsk(String question, String conversationId, String cityId);
}
