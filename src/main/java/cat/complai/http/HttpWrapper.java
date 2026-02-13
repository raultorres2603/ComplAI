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
 * Design notes:
 * - Use a single constructor for DI so Micronaut creates the singleton cleanly.
 * - Provide a package-visible/test-friendly constructor for unit tests to override URL/client/mapper/headers.
 */
@Singleton
public class HttpWrapper {
    // Endpoint and configuration - overridable for testing
    private final String openRouterUrl;
    private final Map<String, String> headers;

    // HttpClient and ObjectMapper are fields to allow injection and easier testing
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    /**
     * DI-friendly constructor used by Micronaut in production.
     * Reads configuration via @Value for easy wiring and sensible defaults.
     */
    @Inject
    public HttpWrapper(HttpClient httpClient,
                       ObjectMapper mapper,
                       @Value("${openrouter.url:https://openrouter.ai/api/v1/chat/completions}") String openRouterUrl,
                       @Value("${OPENROUTER_API_KEY:}") String openRouterApiKey) {
        this.openRouterUrl = openRouterUrl;
        this.httpClient = httpClient == null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() : httpClient;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;

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
            Map<String, Object> payload = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", userPrompt))
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
                    .<HttpDto>thenApply(response -> {
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
