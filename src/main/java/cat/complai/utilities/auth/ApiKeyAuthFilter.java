package cat.complai.utilities.auth;

import cat.complai.dto.openrouter.OpenRouterErrorCode;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Enforces API key authentication on all requests.
 *
 * At startup, scans all JVM environment variables for the prefix
 * {@code API_KEY_}
 * and builds a reverse lookup map {@code apiKey → cityId}. The cityId is the
 * lowercased suffix (e.g. {@code API_KEY_ELPRAT} → {@code "elprat"}).
 *
 * On each request, reads the {@code X-Api-Key} header, looks up the value in
 * the
 * map, and sets the {@code "city"} and {@code "user"} request attributes for
 * downstream controllers.
 *
 * Excluded paths (no key required): GET /, GET /health, GET /health/startup,
 * GET /privacy, /telegram/**.
 *
 * Returning null from a {@code @RequestFilter} method tells Micronaut to
 * continue
 * the filter chain. Returning a response short-circuits immediately.
 */
@Requires(property = "api.key.enabled")
@ServerFilter("/**")
public class ApiKeyAuthFilter {

    private static final Logger logger = Logger.getLogger(ApiKeyAuthFilter.class.getName());

    public static final String CITY_ATTRIBUTE = "city";
    public static final String USER_ATTRIBUTE = "user";

    private final Map<String, String> apiKeyToCityId;
    private final Set<String> disabledCities;

    public ApiKeyAuthFilter() {
        Map<String, String> map = new HashMap<>();
        Set<String> disabled = new HashSet<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().startsWith("API_KEY_")) {
                String cityId = entry.getKey().substring("API_KEY_".length()).toLowerCase();
                map.put(entry.getValue(), cityId);
            }
        }
        if (map.isEmpty()) {
            throw new IllegalStateException(
                    "No API keys configured. Set at least one API_KEY_<CITYID> environment variable.");
        }
        // Discover disabled cities: ENABLE_CITY_<CITYID> must be "true" to enable.
        // Missing or any other value = disabled.
        for (String cityId : new HashSet<>(map.values())) {
            String enabled = System.getenv().getOrDefault("ENABLE_CITY_" + cityId.toUpperCase(), "false");
            if (!"true".equalsIgnoreCase(enabled)) {
                disabled.add(cityId);
            }
        }
        this.apiKeyToCityId = Map.copyOf(map);
        this.disabledCities = Set.copyOf(disabled);
        logger.info(() -> "ApiKeyAuthFilter initialized with " + apiKeyToCityId.size() + " API key(s), "
                + disabledCities.size() + " disabled.");
    }

    // Visible for unit tests — accepts a pre-built map instead of scanning System.getenv()
    public ApiKeyAuthFilter(Map<String, String> apiKeyToCityId) {
        this(apiKeyToCityId, Set.of());
    }

    // Visible for unit tests — accepts both API key map and disabled cities set
    public ApiKeyAuthFilter(Map<String, String> apiKeyToCityId, Set<String> disabledCities) {
        if (apiKeyToCityId.isEmpty()) {
            throw new IllegalStateException("No API keys configured.");
        }
        this.apiKeyToCityId = Map.copyOf(apiKeyToCityId);
        this.disabledCities = Set.copyOf(disabledCities);
        logger.info(
                () -> "ApiKeyAuthFilter initialized with " + this.apiKeyToCityId.size() + " API key(s) (test).");
    }

    @RequestFilter
    @Nullable
    public MutableHttpResponse<?> filter(MutableHttpRequest<?> request) {
        if (isExcluded(request)) {
            return null;
        }

        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            // Allow CORS preflight without authentication
            return null;
        }

        String apiKey = request.getHeaders().get("X-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            logger.warning(() -> "Missing X-Api-Key header — httpStatus=401 method=" + request.getMethod()
                    + " path=" + request.getPath());
            return unauthorizedResponse("Missing X-Api-Key header");
        }

        String cityId = apiKeyToCityId.get(apiKey);
        if (cityId == null) {
            logger.warning(() -> "Invalid API key — httpStatus=401 method=" + request.getMethod()
                    + " path=" + request.getPath());
            return unauthorizedResponse("Invalid API key");
        }

        if (disabledCities.contains(cityId)) {
            logger.info(() -> "City disabled — cityId=" + cityId + " httpStatus=503 method=" + request.getMethod()
                    + " path=" + request.getPath());
            return cityDisabledResponse(cityId);
        }

        request.setAttribute(CITY_ATTRIBUTE, cityId);
        request.setAttribute(USER_ATTRIBUTE, "api-key-client");

        return null;
    }

    private static boolean isExcluded(HttpRequest<?> request) {
        String path = request.getPath();
        HttpMethod method = request.getMethod();

        // Exclude Telegram webhook paths from auth (Telegram cannot send X-Api-Key)
        if (path != null && path.startsWith("/telegram/")) {
            return true;
        }

        return HttpMethod.GET.equals(method)
                && (path.equals("/") || path.equals("/health") || path.equals("/health/startup")
                        || path.equals("/privacy"));
    }

    private MutableHttpResponse<?> unauthorizedResponse(String reason) {
        Map<String, Object> body = Map.of(
                "success", false,
                "message", reason == null ? "Unauthorized" : reason,
                "errorCode", OpenRouterErrorCode.UNAUTHORIZED.getCode());
        return HttpResponse.unauthorized().body(body);
    }

    private MutableHttpResponse<?> cityDisabledResponse(String cityId) {
        Map<String, Object> body = Map.of(
                "success", false,
                "message", "City '" + cityId + "' is currently unavailable",
                "errorCode", OpenRouterErrorCode.CITY_DISABLED.getCode());
        return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * Returns true if the given city is enabled via the
     * {@code ENABLE_CITY_<CITYID>} environment variable.
     * Defaults to disabled (false) when the variable is absent.
     */
    public static boolean isCityEnabled(String cityId) {
        String enabled = System.getenv().getOrDefault("ENABLE_CITY_" + cityId.toUpperCase(), "false");
        return "true".equalsIgnoreCase(enabled);
    }
}
