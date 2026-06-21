package cat.complai.utilities.auth;

import cat.complai.config.CityFeatureFlagService;
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
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Enforces API key authentication on all requests.
 *
 * <p>
 * At startup, scans all JVM environment variables for the prefix
 * {@code API_KEY_}
 * and builds a reverse lookup map {@code apiKey → cityId}. The cityId is the
 * lowercased suffix (e.g. {@code API_KEY_ELPRAT} → {@code "elprat"}).
 *
 * <p>
 * After resolving the cityId, also checks the per-city feature flag
 * ({@code ENABLE_CITY_<cityId>}) via
 * {@link CityFeatureFlagService#isCityEnabled(String)}. If the city is
 * disabled, returns HTTP 503 Service Unavailable.
 *
 * <p>
 * On each request, reads the {@code X-Api-Key} header, looks up the value in
 * the map, and sets the {@code "city"} and {@code "user"} request attributes
 * for
 * downstream controllers.
 *
 * <p>
 * Excluded paths (no key required): GET /, GET /health, GET /health/startup,
 * /telegram/**.
 *
 * <p>
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
    private final CityFeatureFlagService featureFlagService;

    /**
     * Production constructor — scans {@link System#getenv()} for API keys and
     * accepts the feature flag service via DI.
     */
    @Inject
    public ApiKeyAuthFilter(CityFeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
        Map<String, String> map = new HashMap<>();
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
        this.apiKeyToCityId = Map.copyOf(map);
        logger.info(() -> "ApiKeyAuthFilter initialized with " + apiKeyToCityId.size() + " API key(s).");
    }

    /**
     * Visible for unit tests — accepts a pre-built key map instead of scanning
     * {@link System#getenv()}. All cities in the provided map are automatically
     * enabled so existing tests pass unchanged.
     */
    public ApiKeyAuthFilter(Map<String, String> apiKeyToCityId) {
        if (apiKeyToCityId.isEmpty()) {
            throw new IllegalStateException("No API keys configured.");
        }
        this.apiKeyToCityId = Map.copyOf(apiKeyToCityId);
        // Enable all cities from the key map so existing unit tests pass unchanged.
        Map<String, Boolean> enabled = new HashMap<>();
        for (String cityId : apiKeyToCityId.values()) {
            enabled.put(cityId, true);
        }
        this.featureFlagService = new CityFeatureFlagService(enabled);
        logger.info(
                () -> "ApiKeyAuthFilter initialized with " + this.apiKeyToCityId.size() + " API key(s) (test).");
    }

    /**
     * Visible for unit tests — accepts a pre-built feature flag service and a
     * pre-built key map. Allows tests to verify behaviour with disabled cities.
     */
    public ApiKeyAuthFilter(CityFeatureFlagService featureFlagService, Map<String, String> apiKeyToCityId) {
        if (apiKeyToCityId.isEmpty()) {
            throw new IllegalStateException("No API keys configured.");
        }
        this.featureFlagService = featureFlagService;
        this.apiKeyToCityId = Map.copyOf(apiKeyToCityId);
        logger.info(
                () -> "ApiKeyAuthFilter initialized with " + this.apiKeyToCityId.size() + " API key(s) and custom feature flags (test).");
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

        // Check if the city is enabled via the ENABLE_CITY_<cityId> feature flag.
        if (!featureFlagService.isCityEnabled(cityId)) {
            logger.warning(() -> "City disabled — httpStatus=503 cityId=" + cityId
                    + " method=" + request.getMethod() + " path=" + request.getPath());
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
                && (path.equals("/") || path.equals("/health") || path.equals("/health/startup"));
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
                "error", "This city is currently disabled. Please try again later.",
                "errorCode", OpenRouterErrorCode.CITY_DISABLED.getCode());
        return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
