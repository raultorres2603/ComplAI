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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This class is intended to serve as a wrapper for HTTP-related functionalities in the Complai application. It can be used to centralize and manage HTTP requests, responses, and any related logic that may be needed across different parts of the application. This could include handling common headers, managing authentication tokens, or providing utility methods for making HTTP calls to external services.
 */
@Singleton
public class HttpWrapper {
    private final String openRouterUrl = "https://openrouter.ai/api/v1/chat/completions";
    private final Map<String, String> headers = Map.of(
        "Authorization", System.getenv("OPENROUTER_API_KEY"),
            "HTTP-Referer", "https://complai.cat",
            "X-Title", "Complai"
                );
    private final String model = "minimax/minimax-m2.5";

    public HttpWrapper() {
        // Initialize headers, model, and messages as needed
    }

    public HttpDto postToOpenRouter(String userPrompt) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        ObjectMapper mapper = new ObjectMapper();

        // Build payload according to OpenRouter chat completions shape
        Map<String, Object> payload = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", userPrompt))
        );

        try {
            String requestBody = mapper.writeValueAsString(payload);

            // Normalize authorization header (allow raw key in env or already a Bearer token)
            String authValue = headers.get("Authorization");
            if (authValue == null || authValue.isBlank()) {
                authValue = System.getenv("OPENROUTER_API_KEY");
            }
            if (authValue == null) {
                return new HttpDto(null, null, "POST", "Missing OPENROUTER_API_KEY");
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

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            int status = response.statusCode();
            String responseBody = response.body();

            if (status >= 200 && status < 300) {
                String extracted = extractTextFromOpenRouterResponse(responseBody, mapper);
                return new HttpDto(extracted, status, "POST", null);
            } else {
                String err = String.format("OpenRouter non-2xx response: %d", status);
                return new HttpDto(responseBody, status, "POST", err);
            }
        } catch (Exception e) {
            return new HttpDto(null, null, "POST", e.getMessage());
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
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public String getOpenRouterUrl() {
        return openRouterUrl;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getModel() {
        return model;
    }
}
