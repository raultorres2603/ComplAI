package cat.complai.utilities.http;

import cat.complai.dto.http.HttpDto;
import cat.complai.dto.http.OpenRouterStreamStartResult;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.exceptions.OpenRouterStreamingException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Value;
import io.micronaut.retry.annotation.Retryable;
import io.micronaut.http.client.StreamingHttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP wrapper for calling OpenRouter (or compatible) endpoints.
 *
 * <p>
 * Circuit breaker: when OpenRouter is degraded (error rate exceeds 50% over
 * the last N requests) the circuit opens and requests fail fast with a fallback
 * message instead of retrying against an unresponsive provider.
 * </p>
 *
 * <p>
 * Retry policy: on a {@code 429} (upstream rate-limit) or {@code 5xx} response,
 * the request
 * is retried up to {@code maxRetries} times (controlled by
 * {@code OPENROUTER_MAX_RETRIES},
 * default 3). Other 4xx responses and network exceptions are never retried.
 * </p>
 */
@Singleton
public class HttpWrapper {
    // Endpoint and configuration - overridable for testing
    private static final String DEFAULT_OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions";
    private final String openRouterUrl;
    private final Map<String, String> headers;

    // HttpClient and ObjectMapper are fields to allow injection and easier testing
    private final OpenRouterClient openRouterClient;
    private final HttpClient rawStreamingHttpClient;
    private final Scheduler streamScheduler;
    private final ObjectMapper mapper;
    private final Logger logger = Logger.getLogger(HttpWrapper.class.getName());

    private final int requestTimeoutSeconds;
    private final String openRouterModel;
    private final int maxRetries;
    private final CircuitBreaker circuitBreaker;

    /**
     * Exposes the circuit breaker for testing purposes.
     *
     * @return the circuit breaker instance
     */
    CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    /**
     * Protected no-arg constructor to allow tests to subclass HttpWrapper without
     * needing DI.
     * Initializes fields with safe defaults.
     */
    protected HttpWrapper() {
        this.openRouterUrl = "";
        this.openRouterClient = null; // Will be null for test instances
        this.rawStreamingHttpClient = HttpClient.newBuilder().build();
        this.streamScheduler = Schedulers.newSingle("openrouter-stream-test");
        this.mapper = new ObjectMapper();
        this.headers = Map.of();
        this.requestTimeoutSeconds = 60; // default fallback
        this.openRouterModel = "openrouter/free";
        this.maxRetries = 3;
        this.circuitBreaker = new CircuitBreaker();
    }

    @Inject
    public HttpWrapper(OpenRouterClient openRouterClient,
            @Client("${openrouter.url:https://openrouter.ai}") StreamingHttpClient streamingClient,
            @Value("${openrouter.url:}") String openRouterUrl,
            @Value("${OPENROUTER_API_KEY:}") String openRouterApiKey,
            @Value("${OPENROUTER_REQUEST_TIMEOUT_SECONDS:60}") int requestTimeoutSeconds,
            @Value("${OPENROUTER_MODEL:openrouter/free}") String openRouterModel,
            @Value("${OPENROUTER_MAX_RETRIES:3}") int maxRetries,
            @Value("${circuit-breaker.failure-threshold:5}") int cbFailureThreshold,
            @Value("${circuit-breaker.window-size:10}") int cbWindowSize,
            @Value("${circuit-breaker.cooldown-seconds:30}") int cbCooldownSeconds) {
        this.openRouterClient = openRouterClient;
        // If openRouterUrl is empty, fall back to environment variable or default
        String resolvedUrl = (openRouterUrl != null && !openRouterUrl.isBlank())
                ? openRouterUrl
                : System.getenv("OPENROUTER_URL");
        if (resolvedUrl == null || resolvedUrl.isBlank()) {
            resolvedUrl = DEFAULT_OPENROUTER_URL;
        }
        this.openRouterUrl = normalizeOpenRouterBaseUrl(resolvedUrl);
        this.requestTimeoutSeconds = (requestTimeoutSeconds > 0) ? requestTimeoutSeconds : 20; // fallback to 20s if
                                                                                                   // invalid
        this.rawStreamingHttpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(this.requestTimeoutSeconds))
                .build();
        this.streamScheduler = Schedulers.fromExecutorService(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "openrouter-raw-stream");
            t.setDaemon(true);
            return t;
        }));
        this.mapper = new ObjectMapper();
        this.openRouterModel = openRouterModel;
        this.maxRetries = (maxRetries > 0) ? maxRetries : 1; // 1 = no retry, just one attempt
        Map<String, String> h = new HashMap<>();
        if (openRouterApiKey != null && !openRouterApiKey.isBlank()) {
            h.put("Authorization", openRouterApiKey);
        }
        h.put("HTTP-Referer", "https://complai.cat");
        h.put("X-Title", "Complai");
        this.headers = Map.copyOf(h);
        this.circuitBreaker = new CircuitBreaker(cbFailureThreshold, cbWindowSize, cbCooldownSeconds);
    }

    public CompletableFuture<HttpDto> postToOpenRouterAsync(String userPrompt) {
        int promptLength = userPrompt == null ? 0 : userPrompt.length();
        logger.fine(() -> "postToOpenRouterAsync (single-turn) — promptLength=" + promptLength + " model="
                + openRouterModel);
        try {
            // Friendlier, town-tone system message for civilian users from El Prat de
            // Llobregat.
            String systemMessage = """
                    Ets un assistent que es diu Gall Potablava, amable i proper per als veïns d'El Prat de Llobregat. Ajudes a redactar cartes i queixes clares i civils adreçades a l'Ajuntament i ofereixes informació pràctica i local d'El Prat. Mantén les respostes curtes, respectuoses i fàcils de llegir, com un veí que vol ajudar. Si la consulta no és sobre El Prat de Llobregat, digues-ho educadament i explica que no pots ajudar amb aquesta petició; també pots suggerir que facin una pregunta sobre assumptes locals.\


                    En español: Eres un asistente que se llama Gall Potablava amable y cercano para los vecinos de El Prat de Llobregat. Ayuda a redactar cartes i queixes dirigides al Ayuntamiento i ofereix informació pràctica i local. Mantén las respuestas cortas y fáciles de entender. Si la consulta no trata sobre El Prat, dilo educadamente y sugiere que pregunten sobre asuntos locales.\


                    In English (support): You are a friendly local assistant named Gall Potablava for residents of El Prat de Llobregat. Help draft clear, civil letters to the City Hall and provide practical local information. Keep answers short and easy to read. If the request is not about El Prat de Llobregat, politely say you can't help with that request.""";

            Map<String, Object> userMessage = Map.of("role", "user", "content", userPrompt);
            Map<String, Object> systemMsg = Map.of("role", "system", "content", systemMessage);

            Map<String, Object> payload = Map.of(
                    "model", openRouterModel,
                    "messages", List.of(systemMsg, userMessage));
            String requestBody = mapper.writeValueAsString(payload);

            logger.fine(() -> "postToOpenRouterAsync — request body prepared payloadSize=" + requestBody.length()
                    + " model=" + openRouterModel);
            String promptSnippet = userPrompt != null && userPrompt.length() > 200
                    ? userPrompt.substring(0, 200) + "..."
                    : userPrompt;
            logger.finer(() -> "postToOpenRouterAsync — prompt snippet: " + promptSnippet);

            String authValue = resolveAuthHeader();
            if (authValue == null) {
                logger.warning("postToOpenRouterAsync — cannot proceed: OPENROUTER_API_KEY is not configured");
                return CompletableFuture.completedFuture(new HttpDto(null, null, "POST", "Missing OPENROUTER_API_KEY"));
            }

            logger.fine(() -> "postToOpenRouterAsync — sending POST to " + openRouterUrl
                    + " timeoutSeconds=" + requestTimeoutSeconds + " maxRetries=" + maxRetries);
            return sendWithMicronautRetry(payload, authValue);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed preparing request to OpenRouter", e);
            return CompletableFuture.completedFuture(new HttpDto(null, null, "POST", e.getMessage()));
        }
    }

    public CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages) {
        int messageCount = messages == null ? 0 : messages.size();
        logger.fine(() -> "postToOpenRouterAsync (multi-turn) — messageCount=" + messageCount + " model="
                + openRouterModel);
        try {
            Map<String, Object> payload = Map.of(
                    "model", openRouterModel,
                    "messages", messages);
            String requestBody = mapper.writeValueAsString(payload);

            String authValue = resolveAuthHeader();
            if (authValue == null) {
                logger.warning(
                        "postToOpenRouterAsync (multi-turn) — cannot proceed: OPENROUTER_API_KEY is not configured");
                return CompletableFuture.completedFuture(new HttpDto(null, null, "POST", "Missing OPENROUTER_API_KEY"));
            }

            logger.fine(() -> "postToOpenRouterAsync (multi-turn) — sending POST to " + openRouterUrl
                    + " payloadSize=" + requestBody.length()
                    + " timeoutSeconds=" + requestTimeoutSeconds + " maxRetries=" + maxRetries);
            return sendWithMicronautRetry(payload, authValue);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed preparing request to OpenRouter (multi-turn)", e);
            return CompletableFuture.completedFuture(new HttpDto(null, null, "POST", e.getMessage()));
        }
    }

/**
     * Sends request with manual retry logic.
     * Note: @Retryable doesn't work for internal method calls, so we implement retry manually.
     *
     * <p>
     * Retried statuses:
     * <ul>
     * <li>{@code 429} — OpenRouter upstream rate-limit (transient; retrying after a
     * moment succeeds)</li>
     * <li>{@code 5xx} — server-side errors</li>
     * </ul>
     * 4xx responses other than 429 are definitive client errors and are returned
     * immediately without retry.
     * </p>
     */
    CompletableFuture<HttpDto> sendWithMicronautRetry(Map<String, Object> payload, String authValue) {
        // --- Circuit breaker guard: fail fast when circuit is OPEN ---
        if (!circuitBreaker.isCallPermitted()) {
            logger.warning("Circuit breaker OPEN — rejecting request without calling OpenRouter");
            return CompletableFuture.completedFuture(
                    new HttpDto(null, null, "POST",
                            "El servei d'intel·ligència artificial no està disponible en aquests moments. Torneu-ho a intentar d'aquí a uns minuts.",
                            OpenRouterErrorCode.CIRCUIT_OPEN));
        }

        HttpDto finalResult = null;
        int lastStatus = 0;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String responseBody = openRouterClient.chatCompletions(
                        payload,
                        authValue,
                        headers.getOrDefault("HTTP-Referer", "https://complai.cat"),
                        headers.getOrDefault("X-Title", "Complai"));

                String extracted = extractTextFromOpenRouterResponse(responseBody, mapper);
                circuitBreaker.recordSuccess();
                return CompletableFuture.completedFuture(new HttpDto(extracted, 200, "POST", null));

            } catch (HttpClientResponseException e) {
                lastStatus = e.getStatus().getCode();
                String body = e.getResponse().getBody(String.class).orElse("");

                // Retryable error (429 or 5xx) - retry if we have attempts left
                if (lastStatus == 429 || lastStatus >= 500) {
                    logger.warning("OpenRouter retryable error — httpStatus=" + lastStatus + " url=" + openRouterUrl + " attempt=" + attempt + "/" + maxRetries);
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(250L * (long) Math.pow(2, attempt - 1)); // Exponential backoff
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue; // Retry
                    }
                    // No more retries - fall through to return error
                }

                // Non-retryable error or retries exhausted
                logger.warning("OpenRouter non-2xx response — httpStatus=" + lastStatus + " url=" + openRouterUrl);
                finalResult = new HttpDto(body, lastStatus, "POST", String.format("OpenRouter error: %d", lastStatus));
                break; // Don't retry

            } catch (Exception e) {
                logger.log(Level.SEVERE, "OpenRouter request failed — url=" + openRouterUrl + " error=" + e.getMessage(), e);
                lastStatus = 0;
                finalResult = new HttpDto(null, null, "POST", e.getMessage());
                break; // Don't retry
            }
        }

        // After all attempts exhausted (or non-retryable error), record failure
        if (finalResult == null && lastStatus > 0) {
            // Retries exhausted case
            logger.warning("OpenRouter retries exhausted — recording failure for circuit breaker");
            circuitBreaker.recordFailure();
            finalResult = new HttpDto(null, lastStatus, "POST", String.format("OpenRouter error after %d attempts", maxRetries));
        } else if (finalResult != null && finalResult.statusCode() != null && finalResult.statusCode() >= 500) {
            // Non-retryable server error
            circuitBreaker.recordFailure();
        } else if (finalResult != null && finalResult.statusCode() != null && finalResult.statusCode() >= 400 && finalResult.statusCode() != 429) {
            // Non-retryable client error
            circuitBreaker.recordFailure();
        }

return CompletableFuture.completedFuture(finalResult);
    }

    /**
     * Resolves the Bearer token from the pre-configured headers, falling back to the
     * {@code OPENROUTER_API_KEY} environment variable if absent.
     *
     * @return the full "Bearer …" auth value, or {@code null} if no key is
     *         available.
     */
    protected String resolveAuthHeader() {
        String authValue = headers.get("Authorization");
        if (authValue == null || authValue.isBlank()) {
            logger.warning(
                    "Authorization header not present in headers map; falling back to environment variable OPENROUTER_API_KEY");
            authValue = System.getenv("OPENROUTER_API_KEY");
        }
        if (authValue == null) {
            return null;
        }
        return authValue.toLowerCase().startsWith("bearer ") ? authValue : "Bearer " + authValue;
    }

    private String extractTextFromOpenRouterResponse(String responseBody, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            // Try common shapes: choices[].message.content (OpenAI-like)
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

        // If URL starts with scheme-relative '//' (e.g. //openrouter.ai/...), remove
        // leading slashes and prefix https://
        if (url.startsWith("//")) {
            url = url.replaceFirst("^/*", ""); // remove leading slashes
            url = "https://" + url;
        } else {
            String lower = url.toLowerCase();
            // If URL already has an http or https scheme, normalize duplicate slashes after
            // the scheme
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
            logger.log(Level.WARNING, "Configured OpenRouter URL is malformed (original='" + original
                    + "', normalized='" + url + "'), falling back to default URL", e);
            return DEFAULT_OPENROUTER_URL;
        }
    }

    private String normalizeOpenRouterBaseUrl(String url) {
        String normalized = normalizeOpenRouterUrl(url);
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) {
                return "https://openrouter.ai";
            }
            return port > 0 ? (scheme + "://" + host + ":" + port) : (scheme + "://" + host);
        } catch (Exception e) {
            return "https://openrouter.ai";
        }
    }

    /**
     * Streams a chat completion response from OpenRouter using Server-Sent Events.
     * Each emission is one raw SSE line (e.g. {@code data: {...}} or {@code data: [DONE]}).
     *
     * @param messages the full messages list (system + history + user)
     * @return typed stream-start result containing either a ready publisher or a typed startup failure
     */
    public OpenRouterStreamStartResult streamFromOpenRouter(List<Map<String, Object>> messages) {
        // --- Circuit breaker guard: fail fast when circuit is OPEN ---
        if (!circuitBreaker.isCallPermitted()) {
            logger.warning("Circuit breaker OPEN — rejecting streaming request without calling OpenRouter");
            return new OpenRouterStreamStartResult.Error(new OpenRouterStreamingException(
                    OpenRouterErrorCode.CIRCUIT_OPEN,
                    "El servei d'intel·ligència artificial no està disponible en aquests moments. Torneu-ho a intentar d'aquí a uns minuts.",
                    null));
        }

        String authValue = resolveAuthHeader();
        if (authValue == null) {
            logger.warning("streamFromOpenRouter — OPENROUTER_API_KEY is not configured");
            return new OpenRouterStreamStartResult.Error(new OpenRouterStreamingException(
                    OpenRouterErrorCode.INTERNAL,
                    "Streaming authentication is not configured.",
                    null));
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", openRouterModel);
        payload.put("messages", messages);
        payload.put("stream", true);

        final String bodyJson;
        try {
            bodyJson = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return new OpenRouterStreamStartResult.Error(new OpenRouterStreamingException(
                    OpenRouterErrorCode.INTERNAL,
                    "Failed to serialize streaming payload.",
                    null,
                    e));
        }

        URI endpoint = URI.create(openRouterUrl + "/api/v1/chat/completions");
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(endpoint)
                .timeout(java.time.Duration.ofSeconds(requestTimeoutSeconds))
                .header("Authorization", authValue)
                .header("HTTP-Referer", headers.getOrDefault("HTTP-Referer", "https://complai.cat"))
                .header("X-Title", headers.getOrDefault("X-Title", "Complai"))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<Stream<String>> response = rawStreamingHttpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            int status = response.statusCode();
            if (status >= 400) {
                logger.warning(() -> "OpenRouter stream startup failed — upstreamStatus=" + status
                        + " url=" + openRouterUrl);
                circuitBreaker.recordFailure();
                response.body().close();
                return new OpenRouterStreamStartResult.Error(new OpenRouterStreamingException(
                        OpenRouterErrorCode.UPSTREAM,
                        "OpenRouter stream startup failed.",
                        status));
            }

            // HTTP 200: stream accepted by OpenRouter
            circuitBreaker.recordSuccess();

            Publisher<String> stream = Flux.using(
                    response::body,
                    body -> Flux.fromStream(body)
                            .map(String::trim)
                            .filter(line -> !line.isBlank() && !line.startsWith(":")),
                    Stream::close)
                    .subscribeOn(streamScheduler)
                    .timeout(java.time.Duration.ofSeconds(requestTimeoutSeconds + 5L))
                    .onErrorMap(this::mapStreamingException);
            return new OpenRouterStreamStartResult.Success(stream);
        } catch (HttpTimeoutException e) {
            logger.warning(() -> "OpenRouter stream startup timed out — url=" + openRouterUrl);
            circuitBreaker.recordFailure();
            return new OpenRouterStreamStartResult.Error(new OpenRouterStreamingException(
                    OpenRouterErrorCode.TIMEOUT,
                    "OpenRouter stream startup timed out.",
                    null,
                    e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            circuitBreaker.recordFailure();
            return new OpenRouterStreamStartResult.Error(new OpenRouterStreamingException(
                    OpenRouterErrorCode.INTERNAL,
                    "OpenRouter stream startup was interrupted.",
                    null,
                    e));
        } catch (Exception e) {
            logger.log(Level.WARNING, "OpenRouter stream startup failed", e);
            circuitBreaker.recordFailure();
            return new OpenRouterStreamStartResult.Error(new OpenRouterStreamingException(
                    OpenRouterErrorCode.UPSTREAM,
                    "OpenRouter stream startup failed.",
                    null,
                    e));
        }
    }

    private OpenRouterStreamingException mapStreamingException(Throwable error) {
        if (error instanceof OpenRouterStreamingException streamError) {
            return streamError;
        }
        if (error instanceof HttpTimeoutException || error instanceof java.util.concurrent.TimeoutException) {
            return new OpenRouterStreamingException(OpenRouterErrorCode.TIMEOUT,
                    "OpenRouter streaming timed out.", null, error);
        }
        return new OpenRouterStreamingException(OpenRouterErrorCode.UPSTREAM,
                "OpenRouter streaming failed.", null, error);
    }
}
// Timeout is configurable via OPENROUTER_REQUEST_TIMEOUT_SECONDS (default 60s).
// Retry count is configurable via OPENROUTER_MAX_RETRIES (default 3). 429 and
// 5xx responses are retried.
