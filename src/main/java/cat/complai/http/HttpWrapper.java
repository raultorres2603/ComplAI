package cat.complai.http;

import cat.complai.http.dto.HttpDto;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Value;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP wrapper for calling OpenRouter (or compatible) endpoints.
 */
@Singleton
public class HttpWrapper {
    // Endpoint and configuration - overridable for testing
    private final String openRouterUrl;
    private final Map<String, String> headers;

    // HttpClient and ObjectMapper are fields to allow injection and easier testing
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    @Inject
    public HttpWrapper(@Value("${openrouter.url:https://openrouter.ai/api/v1/chat/completions}") String openRouterUrl,
                       @Value("${OPENROUTER_API_KEY:}") String openRouterApiKey) {
        this.openRouterUrl = openRouterUrl;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = new ObjectMapper();

        Map<String, String> h = new HashMap<>();
        if (openRouterApiKey != null && !openRouterApiKey.isBlank()) {
            h.put("Authorization", openRouterApiKey);
        }
        h.put("HTTP-Referer", "https://complai.cat");
        h.put("X-Title", "Complai");
        this.headers = Map.copyOf(h);
    }

    public CompletableFuture<HttpDto> postToOpenRouterAsync(String userPrompt) {
        try {
            String model = "minimax/minimax-m2.5";

            // System message defining assistant persona and constraint to El Prat de Llobregat.
            String systemMessage = "You are an assistant that helps users draft formal letters and complaints to the City Hall (Ajuntament) of El Prat de Llobregat, provides local information about El Prat de Llobregat, and supports or clarifies users' opinions only when they are explicitly about El Prat de Llobregat. If the user's request is not about El Prat de Llobregat, reply that you can't help with that request.";

            Map<String, Object> userMessage = Map.of("role", "user", "content", userPrompt);
            Map<String, Object> systemMsg = Map.of("role", "system", "content", systemMessage);

            Map<String, Object> payload = Map.of(
                    "model", model,
                    "messages", List.of(systemMsg, userMessage)
            );
            String requestBody = mapper.writeValueAsString(payload);

            String authValue = headers.get("Authorization");
            if (authValue == null || authValue.isBlank()) {
                authValue = System.getenv("OPENROUTER_API_KEY");
            }
            if (authValue == null) {
                return CompletableFuture.completedFuture(new HttpDto(null, null, "POST", "Missing OPENROUTER_API_KEY"));
            }
            if (!authValue.toLowerCase().startsWith("bearer ")) {
                authValue = "Bearer " + authValue;
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(openRouterUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authValue)
                    .header("Referer", headers.getOrDefault("HTTP-Referer", "https://complai.cat"))
                    .header("X-Title", headers.getOrDefault("X-Title", "complai"))
                    .POST(BodyPublishers.ofString(requestBody))
                    .build();

            return httpClient.sendAsync(request, BodyHandlers.ofString())
                    .thenApply(response -> {
                        int status = response.statusCode();
                        String body = response.body();
                        if (status >= 200 && status < 300) {
                            String extracted = extractTextFromOpenRouterResponse(body, mapper);
                            return new HttpDto(extracted, status, "POST", null);
                        } else {
                            return new HttpDto(body, status, "POST", String.format("OpenRouter non-2xx response: %d", status));
                        }
                    })
                    .exceptionally(ex -> new HttpDto(null, null, "POST", ex.getMessage()));

        } catch (Exception e) {
            return CompletableFuture.completedFuture(new HttpDto(null, null, "POST", e.getMessage()));
        }
    }

    private String extractTextFromOpenRouterResponse(String responseBody, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            // Try common shapes: choices[].message.content  (OpenAI-like)
            if (root.has("choices") && root.get("choices").isArray() && !root.get("choices").isEmpty()) {
                JsonNode first = root.get("choices").get(0);
                if (first.has("message") && first.get("message").has("content")) {
                    return first.get("message").get("content").asText();
                }
                if (first.has("text")) {
                    return first.get("text").asText();
                }
            }
            return null;
        } catch (Exception e) {
            // If parsing fails, return null (caller handles it)
            return null;
        }
    }
}
