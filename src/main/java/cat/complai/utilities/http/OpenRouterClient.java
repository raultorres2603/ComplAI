package cat.complai.utilities.http;

import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Singleton;

import java.util.Map;

/**
 * Micronaut HTTP client interface for OpenRouter API.
 * This replaces the direct java.net.http.HttpClient usage with a managed,
 * configurable Micronaut client that provides better integration with
 * DI, metrics, pooling, and timeouts.
 */
@Client("${openrouter.url:https://openrouter.ai}")
@Singleton
public interface OpenRouterClient {

    /**
     * Send a chat completion request to OpenRouter API.
     * 
     * @param payload The request body as a Map (will be serialized to JSON)
     * @param authorization The Bearer token authorization header
     * @param referer The HTTP-Referer header for OpenRouter analytics
     * @param title The X-Title header for OpenRouter analytics
     * @return The response body as a String
     */
    @Post("/api/v1/chat/completions")
    String chatCompletions(
            @Body Map<String, Object> payload,
            @Header("Authorization") String authorization,
            @Header("HTTP-Referer") String referer,
            @Header("X-Title") String title
    );
}
