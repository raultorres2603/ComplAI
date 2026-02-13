package cat.complai.http;

import cat.complai.http.dto.HttpDto;
import jakarta.inject.Singleton;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * This class is intended to serve as a wrapper for HTTP-related functionalities in the Complai application. It can be used to centralize and manage HTTP requests, responses, and any related logic that may be needed across different parts of the application. This could include handling common headers, managing authentication tokens, or providing utility methods for making HTTP calls to external services.
 */
@Singleton
public class HttpWrapper {
    // Endpoint and configuration - default values provided but overridable for testing
    private final String openRouterUrl;
    private final Map<String, String> headers;
    private final String model = "minimax/minimax-m2.5";

    // HttpClient and ObjectMapper are fields to allow injection and easier testing
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    // Default constructor used by the application
    public HttpWrapper() {
        this.openRouterUrl = "https://openrouter.ai/api/v1/chat/completions";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
        // Safely initialize headers: only include Authorization if the env var is present
        Map<String, String> h = new HashMap<>();
        String apiKeyEnv = System.getenv("OPENROUTER_API_KEY");
        if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
            h.put("Authorization", apiKeyEnv);
        }
        h.put("HTTP-Referer", "https://complai.cat");
        h.put("X-Title", "Complai");
        this.headers = Map.copyOf(h);
    }

    // Test-friendly constructor to override URL/client/mapper
    public HttpWrapper(String openRouterUrl, HttpClient httpClient, ObjectMapper mapper) {
        this.openRouterUrl = openRouterUrl;
        this.httpClient = httpClient;
        this.mapper = mapper;
        Map<String, String> h = new HashMap<>();
        String apiKeyEnv = System.getenv("OPENROUTER_API_KEY");
        if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
            h.put("Authorization", apiKeyEnv);
        }
        h.put("HTTP-Referer", "https://complai.cat");
        h.put("X-Title", "Complai");
        this.headers = Map.copyOf(h);
    }

    // Additional constructor allowing test to provide headers explicitly (e.g., bypass env)
    public HttpWrapper(String openRouterUrl, HttpClient httpClient, ObjectMapper mapper, Map<String, String> headers) {
        this.openRouterUrl = openRouterUrl;
        this.httpClient = httpClient;
        this.mapper = mapper;
        Map<String, String> h = new HashMap<>(headers == null ? Map.of() : headers);
        // ensure referer and title defaults exist if not provided
        h.putIfAbsent("HTTP-Referer", "https://complai.cat");
        h.putIfAbsent("X-Title", "Complai");
        this.headers = Map.copyOf(h);
    }

    public CompletableFuture<HttpDto> postToOpenRouterAsync(String userPrompt) {
        try {
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
            // If parsing fails, return raw body
            return null;
        }
    }
}
