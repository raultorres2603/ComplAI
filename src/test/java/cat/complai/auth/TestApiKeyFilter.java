package cat.complai.auth;

import cat.complai.openrouter.dto.OpenRouterErrorCode;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Singleton;

import java.util.Map;

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

        request.setAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, cityId);
        request.setAttribute(ApiKeyAuthFilter.USER_ATTRIBUTE, "api-key-client");

        return null;
    }

    private boolean isExcluded(HttpRequest<?> request) {
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
}
