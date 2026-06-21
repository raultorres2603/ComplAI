package cat.complai.utilities.auth;

import cat.complai.dto.openrouter.OpenRouterErrorCode;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Singleton;

import java.util.Map;

/**
 * Test replacement for {@link ApiKeyAuthFilter}.
 *
 * <p>Uses hardcoded API keys for integration tests. The per-city feature flag
 * is checked directly via the {@code ENABLE_CITY_<cityId>} environment variable,
 * matching the logic in {@link cat.complai.config.CityFeatureFlagService}.
 *
 * <p>Integration tests that need to simulate a disabled city should set the
 * {@code ENABLE_CITY_<cityId>} environment variable to {@code "false"} or unset
 * it at the JVM level. This test filter reads env vars directly rather than
 * injecting the service to avoid DI resolution ordering issues in the test
 * Micronaut context.
 */
@Singleton
@ServerFilter("/**")
@Replaces(ApiKeyAuthFilter.class)
public class TestApiKeyFilter {

    private final Map<String, String> apiKeyToCityId = Map.of(
            "test-integration-key-elprat", "elprat",
            "test-integration-key-testcity", "testcity",
            "test-api-key-feedback", "elprat",
            "test-integration-key-elprat-htmlsources", "testcity");

    @RequestFilter
    @Nullable
    public MutableHttpResponse<?> filter(MutableHttpRequest<?> request) {
        if (isExcluded(request)) {
            return null;
        }

        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return null;
        }

        String apiKey = request.getHeaders().get("X-Api-Key");
        if (apiKey == null || apiKey.isBlank()) {
            return unauthorizedResponse("Missing X-Api-Key header");
        }

        String cityId = apiKeyToCityId.get(apiKey);
        if (cityId == null) {
            return unauthorizedResponse("Invalid API key");
        }

        // Check if the city is enabled via the ENABLE_CITY_<cityId> feature flag.
        // Uses Boolean.parseBoolean() which returns false for any value other than "true".
        if (!isCityEnabled(cityId)) {
            return cityDisabledResponse(cityId);
        }

        request.setAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, cityId);
        request.setAttribute(ApiKeyAuthFilter.USER_ATTRIBUTE, "api-key-client");

        return null;
    }

    /**
     * Matches the logic in {@link cat.complai.config.CityFeatureFlagService#isCityEnabled}.
     */
    private static boolean isCityEnabled(String cityId) {
        if (cityId == null) {
            return false;
        }
        String envValue = System.getenv("ENABLE_CITY_" + cityId.toUpperCase());
        return Boolean.parseBoolean(envValue);
    }

    private static boolean isExcluded(HttpRequest<?> request) {
        String path = request.getPath();
        HttpMethod method = request.getMethod();
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
