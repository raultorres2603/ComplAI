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

    /**
     * Protected no-arg constructor to allow tests to subclass HttpWrapper without needing DI.
     * Initializes fields with safe defaults.
     */
    protected HttpWrapper() {
        this.openRouterUrl = "";
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = new ObjectMapper();
        this.headers = Map.of();
    }

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

            // Friendlier, town-tone system message for civilian users from El Prat de Llobregat.
            String systemMessage = "Ets un assistent amable i proper per als veïns d'El Prat de Llobregat. Ajudes a redactar cartes i queixes clares i civils adreçades a l'Ajuntament i ofereixes informació pràctica i local d'El Prat. Mantén les respostes curtes, respectuoses i fàcils de llegir, com un veí que vol ajudar. Si la consulta no és sobre El Prat de Llobregat, digues-ho educadament i explica que no pots ajudar amb aquesta petició; també pots suggerir que facin una pregunta sobre assumptes locals." +
                    "\n\nEn español: Eres un asistente amable y cercano para los vecinos de El Prat de Llobregat. Ayuda a redactar cartes i queixes dirigides al Ayuntamiento i ofereix informació pràctica i local. Mantén las respuestas cortas y fáciles de entender. Si la consulta no trata sobre El Prat, dilo educadamente y sugiere que pregunten sobre asuntos locales." +
                    "\n\nIn English (support): You are a friendly local assistant for residents of El Prat de Llobregat. Help draft clear, civil letters to the City Hall and provide practical local information. Keep answers short and easy to read. If the request is not about El Prat de Llobregat, politely say you can't help with that request.";

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
