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
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP wrapper for calling OpenRouter (or compatible) endpoints.
 *
 * <p>Retry policy: on a {@code 429} (upstream rate-limit) or {@code 5xx} response, the request
 * is retried up to {@code maxRetries} times (controlled by {@code OPENROUTER_MAX_RETRIES},
 * default 3). Other 4xx responses and network exceptions are never retried.</p>
 */
@Singleton
public class HttpWrapper {
    // Endpoint and configuration - overridable for testing
    private static final String DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    private final String openRouterUrl;
    private final Map<String, String> headers;

    // HttpClient and ObjectMapper are fields to allow injection and easier testing
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Logger logger = Logger.getLogger(HttpWrapper.class.getName());

    private final int requestTimeoutSeconds;
    private final String openRouterModel;
    private final int maxRetries;

    /**
     * Protected no-arg constructor to allow tests to subclass HttpWrapper without needing DI.
     * Initializes fields with safe defaults.
     */
    protected HttpWrapper() {
        this.openRouterUrl = "";
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = new ObjectMapper();
        this.headers = Map.of();
        this.requestTimeoutSeconds = 60; // default fallback
        this.openRouterModel = "openrouter/free";
        this.maxRetries = 3;
    }

    @Inject
    public HttpWrapper(@Value("${openrouter.url:https://openrouter.ai/api/v1/chat/completions}") String openRouterUrl,
                       @Value("${OPENROUTER_API_KEY:}") String openRouterApiKey,
                       @Value("${OPENROUTER_REQUEST_TIMEOUT_SECONDS:60}") int requestTimeoutSeconds,
                       @Value("${OPENROUTER_MODEL:openrouter/free}") String openRouterModel,
                       @Value("${OPENROUTER_MAX_RETRIES:3}") int maxRetries) {
        this.openRouterUrl = normalizeOpenRouterUrl(openRouterUrl);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.mapper = new ObjectMapper();
        this.requestTimeoutSeconds = (requestTimeoutSeconds > 0) ? requestTimeoutSeconds : 20; // fallback to 20s if invalid
        this.openRouterModel = openRouterModel;
        this.maxRetries = (maxRetries > 0) ? maxRetries : 1; // 1 = no retry, just one attempt
        Map<String, String> h = new HashMap<>();
        if (openRouterApiKey != null && !openRouterApiKey.isBlank()) {
            h.put("Authorization", openRouterApiKey);
        }
        h.put("HTTP-Referer", "https://complai.cat");
        h.put("X-Title", "Complai");
        this.headers = Map.copyOf(h);
    }

    public CompletableFuture<HttpDto> postToOpenRouterAsync(String userPrompt) {
        int promptLength = userPrompt == null ? 0 : userPrompt.length();
        logger.fine(() -> "postToOpenRouterAsync (single-turn) — promptLength=" + promptLength + " model=" + openRouterModel);
        try {
            // Friendlier, town-tone system message for civilian users from El Prat de Llobregat.
            String systemMessage = """
                    Ets un assistent que es diu Gall Potablava, amable i proper per als veïns d'El Prat de Llobregat. Ajudes a redactar cartes i queixes clares i civils adreçades a l'Ajuntament i ofereixes informació pràctica i local d'El Prat. Mantén les respostes curtes, respectuoses i fàcils de llegir, com un veí que vol ajudar. Si la consulta no és sobre El Prat de Llobregat, digues-ho educadament i explica que no pots ajudar amb aquesta petició; també pots suggerir que facin una pregunta sobre assumptes locals.\
                    
                    
                    En español: Eres un asistente que se llama Gall Potablava amable y cercano para los vecinos de El Prat de Llobregat. Ayuda a redactar cartes i queixes dirigides al Ayuntamiento i ofereix informació pràctica i local. Mantén las respuestas cortas y fáciles de entender. Si la consulta no trata sobre El Prat, dilo educadamente y sugiere que pregunten sobre asuntos locales.\
                    
                    
                    In English (support): You are a friendly local assistant named Gall Potablava for residents of El Prat de Llobregat. Help draft clear, civil letters to the City Hall and provide practical local information. Keep answers short and easy to read. If the request is not about El Prat de Llobregat, politely say you can't help with that request.""";

            Map<String, Object> userMessage = Map.of("role", "user", "content", userPrompt);
            Map<String, Object> systemMsg = Map.of("role", "system", "content", systemMessage);

            Map<String, Object> payload = Map.of(
                    "model", openRouterModel,
                    "messages", List.of(systemMsg, userMessage)
            );
            String requestBody = mapper.writeValueAsString(payload);

            logger.fine(() -> "postToOpenRouterAsync — request body prepared payloadSize=" + requestBody.length()
                    + " model=" + openRouterModel);
            logger.finer(() -> "postToOpenRouterAsync — prompt snippet: "
                    + (userPrompt.length() > 200 ? userPrompt.substring(0, 200) + "..." : userPrompt));

            String authValue = resolveAuthHeader();
            if (authValue == null) {
                logger.warning("postToOpenRouterAsync — cannot proceed: OPENROUTER_API_KEY is not configured");
                return CompletableFuture.completedFuture(new HttpDto(null, null, "POST", "Missing OPENROUTER_API_KEY"));
            }

            HttpRequest request = buildHttpRequest(requestBody, authValue);
            logger.fine(() -> "postToOpenRouterAsync — sending POST to " + openRouterUrl
                    + " timeoutSeconds=" + requestTimeoutSeconds + " maxRetries=" + maxRetries);
            return sendWithRetry(request, maxRetries);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed preparing request to OpenRouter", e);
            return CompletableFuture.completedFuture(new HttpDto(null, null, "POST", e.getMessage()));
        }
    }

    public CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages) {
        int messageCount = messages == null ? 0 : messages.size();
        logger.fine(() -> "postToOpenRouterAsync (multi-turn) — messageCount=" + messageCount + " model=" + openRouterModel);
        try {
            Map<String, Object> payload = Map.of(
                    "model", openRouterModel,
                    "messages", messages
            );
            String requestBody = mapper.writeValueAsString(payload);

            String authValue = resolveAuthHeader();
            if (authValue == null) {
                logger.warning("postToOpenRouterAsync (multi-turn) — cannot proceed: OPENROUTER_API_KEY is not configured");
                return CompletableFuture.completedFuture(new HttpDto(null, null, "POST", "Missing OPENROUTER_API_KEY"));
            }

            HttpRequest request = buildHttpRequest(requestBody, authValue);
            logger.fine(() -> "postToOpenRouterAsync (multi-turn) — sending POST to " + openRouterUrl
                    + " payloadSize=" + requestBody.length()
                    + " timeoutSeconds=" + requestTimeoutSeconds + " maxRetries=" + maxRetries);
            return sendWithRetry(request, maxRetries);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed preparing request to OpenRouter (multi-turn)", e);
            return CompletableFuture.completedFuture(new HttpDto(null, null, "POST", e.getMessage()));
        }
    }

    /**
     * Sends {@code request} and retries on transient failures until {@code attemptsLeft} reaches 1.
     *
     * <p>Retried statuses:
     * <ul>
     *   <li>{@code 429} — OpenRouter upstream rate-limit (transient; retrying after a moment succeeds)</li>
     *   <li>{@code 5xx} — server-side errors</li>
     * </ul>
     * 4xx responses other than 429 are definitive client errors and are returned immediately.
     * Network exceptions are also not retried — they are unlikely to self-heal within the same
     * request lifecycle.</p>
     */
    private CompletableFuture<HttpDto> sendWithRetry(HttpRequest request, int attemptsLeft) {
        int attemptNumber = maxRetries - attemptsLeft + 1;
        return httpClient.sendAsync(request, BodyHandlers.ofString())
                .thenCompose(response -> {
                    int status = response.statusCode();
                    String body = response.body();
                    int bodyLength = body == null ? 0 : body.length();
                    logger.fine(() -> "OpenRouter response — httpStatus=" + status
                            + " bodyLength=" + bodyLength
                            + " attempt=" + attemptNumber + "/" + maxRetries
                            + " url=" + openRouterUrl);

                    if ((status == 429 || status >= 500) && attemptsLeft > 1) {
                        String reason = status == 429 ? "rate-limited" : "server error";
                        long baseDelayMs = 250L;
                        long expDelayMs = baseDelayMs * (1L << Math.min(attemptNumber - 1, 3));
                        long jitterMs = ThreadLocalRandom.current().nextLong(0, 150);
                        long delayMs = Math.min(2000L, expDelayMs + jitterMs);
                        logger.warning(() -> "OpenRouter " + reason + " — httpStatus=" + status
                                + " retrying attempt=" + (attemptNumber + 1) + "/" + maxRetries
                                + " backoffMs=" + delayMs
                                + " url=" + openRouterUrl);
                        return CompletableFuture
                                .runAsync(() -> {}, CompletableFuture.delayedExecutor(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS))
                                .thenCompose(ignored -> sendWithRetry(request, attemptsLeft - 1));
                    }

                    if (status >= 200 && status < 300) {
                        String extracted = extractTextFromOpenRouterResponse(body, mapper);
                        logger.fine(() -> "OpenRouter success — httpStatus=" + status
                                + " extractedLength=" + (extracted == null ? 0 : extracted.length())
                                + " attempt=" + attemptNumber + "/" + maxRetries);
                        return CompletableFuture.completedFuture(new HttpDto(extracted, status, "POST", null));
                    }

                    logger.warning(() -> "OpenRouter non-2xx response — httpStatus=" + status
                            + " attempt=" + attemptNumber + "/" + maxRetries
                            + " url=" + openRouterUrl);
                    return CompletableFuture.completedFuture(
                            new HttpDto(body, status, "POST", String.format("OpenRouter non-2xx response: %d", status)));
                })
                .exceptionally(ex -> {
                    logger.log(Level.SEVERE, "OpenRouter request failed — attempt=" + attemptNumber + "/" + maxRetries
                            + " url=" + openRouterUrl + " error=" + ex.getMessage(), ex);
                    return new HttpDto(null, null, "POST", ex.getMessage());
                });
    }

    /**
     * Resolves the Bearer token from the pre-configured headers, falling back to the
     * {@code OPENROUTER_API_KEY} environment variable if absent.
     *
     * @return the full "Bearer …" auth value, or {@code null} if no key is available.
     */
    private String resolveAuthHeader() {
        String authValue = headers.get("Authorization");
        if (authValue == null || authValue.isBlank()) {
            logger.warning("Authorization header not present in headers map; falling back to environment variable OPENROUTER_API_KEY");
            authValue = System.getenv("OPENROUTER_API_KEY");
        }
        if (authValue == null) {
            return null;
        }
        return authValue.toLowerCase().startsWith("bearer ") ? authValue : "Bearer " + authValue;
    }

    private HttpRequest buildHttpRequest(String requestBody, String authValue) {
        return HttpRequest.newBuilder()
                .uri(URI.create(openRouterUrl))
                .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", authValue)
                .header("Referer", headers.getOrDefault("HTTP-Referer", "https://complai.cat"))
                .header("X-Title", headers.getOrDefault("X-Title", "complai"))
                .POST(BodyPublishers.ofString(requestBody))
                .build();
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
            logger.log(Level.FINE, "Failed to parse OpenRouter response JSON", e);
            return null;
        }
    }

    private String normalizeOpenRouterUrl(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_OPENROUTER_URL;
        }
        String original = url;
        url = url.trim();

        // If URL starts with scheme-relative '//' (e.g. //openrouter.ai/...), remove leading slashes and prefix https://
        if (url.startsWith("//")) {
            url = url.replaceFirst("^/*", ""); // remove leading slashes
            url = "https://" + url;
        } else {
            String lower = url.toLowerCase();
            // If URL already has an http or https scheme, normalize duplicate slashes after the scheme
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                // Collapse any number of slashes after the scheme to exactly two
                url = url.replaceFirst("^(https?:)/*", "$1//");
            } else {
                // No scheme present: default to https
                url = "https://" + url;
            }
        }

        // Validate by attempting to create a URI
        try {
            URI uri = URI.create(url);
            return uri.toString();
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, "Configured OpenRouter URL is malformed (original='" + original + "', normalized='" + url + "'), falling back to default URL", e);
            return DEFAULT_OPENROUTER_URL;
        }
    }
}
// Timeout is configurable via OPENROUTER_REQUEST_TIMEOUT_SECONDS (default 60s).
// Retry count is configurable via OPENROUTER_MAX_RETRIES (default 3). 429 and 5xx responses are retried.
