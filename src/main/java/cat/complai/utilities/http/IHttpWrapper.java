package cat.complai.utilities.http;

import cat.complai.dto.http.HttpDto;
import cat.complai.dto.http.OpenRouterStreamStartResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for the HTTP wrapper that calls OpenRouter endpoints.
 *
 * <p>
 * Depend on this abstraction instead of the concrete {@link HttpWrapper} class
 * to follow the Dependency Inversion Principle.
 * </p>
 */
public interface IHttpWrapper {

    /**
     * Sends a single-turn prompt asynchronously to OpenRouter.
     *
     * @param userPrompt the user's text prompt
     * @return the HTTP result as a CompletableFuture
     */
    CompletableFuture<HttpDto> postToOpenRouterAsync(String userPrompt);

    /**
     * Sends a multi-turn conversation asynchronously to OpenRouter.
     *
     * @param messages the list of message maps (role + content)
     * @return the HTTP result as a CompletableFuture
     */
    CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages);

    /**
     * Streams a chat completion response from OpenRouter using Server-Sent Events.
     *
     * @param messages the full messages list (system + history + user)
     * @return typed stream-start result with either a ready publisher or a startup
     *         failure
     */
    OpenRouterStreamStartResult streamFromOpenRouter(List<Map<String, Object>> messages);
}
