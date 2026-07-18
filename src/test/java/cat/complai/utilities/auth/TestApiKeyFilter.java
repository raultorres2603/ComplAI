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
import java.util.Set;

@Singleton
@ServerFilter("/**")
@Replaces(ApiKeyAuthFilter.class)
public class TestApiKeyFilter {

    private final Map<String, String> apiKeyToCityId = Map.of(
            "test-integration-key-elprat", "elprat",
            "test-integration-key-testcity", "testcity",
            "test-api-key-feedback", "elprat",
            "test-integration-key-elprat-htmlsources", "testcity");

    /** Cities that should return 503 CITY_DISABLED in tests. */
    private final Set<String> disabledCities;

    public TestApiKeyFilter() {
        // Discover disabled cities from ENABLE_CITY_<CITYID> env vars.
        // In tests, cities are enabled by default unless ENABLE_CITY_X=false is set.
        this.disabledCities = apiKeyToCityId.values().stream()
                .distinct()
                .filter(cityId -> {
                    String enabled = System.getenv().getOrDefault("ENABLE_CITY_" + cityId.toUpperCase(), "true");
                    return "false".equalsIgnoreCase(enabled);
                })
                .collect(java.util.stream.Collectors.toSet());
    }

    // Visible for tests — accepts a custom disabled cities set
    public TestApiKeyFilter(Set<String> disabledCities) {
        this.disabledCities = Set.copyOf(disabledCities);
    }

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

        if (disabledCities.contains(cityId)) {
            return cityDisabledResponse(cityId);
        }

        request.setAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, cityId);
        request.setAttribute(ApiKeyAuthFilter.USER_ATTRIBUTE, "api-key-client");

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
}
