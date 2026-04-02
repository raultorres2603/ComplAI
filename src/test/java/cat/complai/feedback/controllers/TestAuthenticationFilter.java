package cat.complai.feedback.controllers;

import cat.complai.auth.ApiKeyAuthFilter;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Singleton;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Test authentication filter that provides API key handling for /complai/* endpoints
 * during test execution. This allows tests to use a test API key without requiring
 * System.getenv() configuration.
 */
@Singleton
@Requires(env = "feedback-test")
@ServerFilter("/**")
public class TestAuthenticationFilter {
    private static final Logger logger = Logger.getLogger(TestAuthenticationFilter.class.getName());

    @RequestFilter
    @Nullable
    public MutableHttpResponse<?> filter(MutableHttpRequest<?> request) {
        String path = request.getPath();
        
        // Only handle /complai/** requests
        if (!path.startsWith("/complai/")) {
            return null;
        }

        // Try to get API key from either X-Api-Key or x-api-key (headers are often case-insensitive)
        String apiKey = request.getHeaders().get("X-Api-Key");
        if (apiKey == null) {
            apiKey = request.getHeaders().get("x-api-key");
        }
        logger.info("TestAuthFilter: path=" + path + ", apiKey=" + (apiKey == null ? "null" : "***"));
        
        // Test API key recognized — set attributes and continue
        if ("test-api-key-feedback".equals(apiKey)) {
            request.setAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, "elprat");
            request.setAttribute(ApiKeyAuthFilter.USER_ATTRIBUTE, "api-key-client");
            logger.info("TestAuthFilter: authenticated test API key, city=elprat");
            return null;
        }

        // No key or invalid key — return 401
        if (apiKey == null || apiKey.isBlank()) {
            logger.warning("TestAuthFilter: missing API key for path=" + path);
            return unauthorizedResponse("Missing X-Api-Key header");
        }

        logger.warning("TestAuthFilter: invalid API key for path=" + path + ", got=" + apiKey);
        return unauthorizedResponse("Invalid API key");
    }

    private MutableHttpResponse<?> unauthorizedResponse(String reason) {
        Map<String, Object> body = Map.of(
                "success", false,
                "message", reason,
                "errorCode", "UNAUTHORIZED");
        return HttpResponse.unauthorized().body(body);
    }
}
