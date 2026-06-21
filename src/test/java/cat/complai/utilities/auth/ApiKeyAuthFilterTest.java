package cat.complai.utilities.auth;

import cat.complai.config.CityFeatureFlagService;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests ApiKeyAuthFilter in isolation by calling filter() directly and inspecting
 * the return value.
 * null → request is allowed through (chain continues).
 * non-null → request is blocked (the returned response is sent immediately).
 */
class ApiKeyAuthFilterTest {

    private ApiKeyAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(Map.of("test-key-elprat", "elprat"));
    }

    // --- Excluded paths bypass API key check entirely ---

    @Test
    void getRoot_noKey_returnsNull() {
        MutableHttpRequest<?> request = HttpRequest.GET("/");
        assertNull(filter.filter(request), "GET / must pass through without a key");
    }

    @Test
    void getHealth_noKey_returnsNull() {
        MutableHttpRequest<?> request = HttpRequest.GET("/health");
        assertNull(filter.filter(request), "GET /health must pass through without a key");
    }

    @Test
    void getHealthStartup_noKey_returnsNull() {
        MutableHttpRequest<?> request = HttpRequest.GET("/health/startup");
        assertNull(filter.filter(request), "GET /health/startup must pass through without a key");
    }

    // POST to / is NOT excluded — it requires an API key.
    @Test
    void postRoot_noKey_returns401() {
        MutableHttpRequest<?> request = HttpRequest.POST("/", "{}");
        MutableHttpResponse<?> response = filter.filter(request);
        assertNotNull(response);
        assertEquals(401, response.getStatus().getCode());
    }

    // --- Missing X-Api-Key header ---

    @Test
    void missingApiKeyHeader_returns401() {
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}");
        MutableHttpResponse<?> response = filter.filter(request);
        assertNotNull(response);
        assertEquals(401, response.getStatus().getCode());
    }

    // --- Invalid API key ---

    @Test
    void wrongApiKey_returns401() {
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "invalid-key");
        MutableHttpResponse<?> response = filter.filter(request);
        assertNotNull(response);
        assertEquals(401, response.getStatus().getCode());
    }

    // --- Valid API key passes through ---

    @Test
    void validApiKey_returnsNull() {
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "test-key-elprat");
        assertNull(filter.filter(request), "Valid API key must pass through (return null)");
    }

    @Test
    void validApiKey_setsCityAttribute() {
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "test-key-elprat");
        filter.filter(request);
        assertEquals("elprat", request.getAttribute("city", String.class).orElse(""));
    }

    @Test
    void validApiKey_setsUserAttribute() {
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "test-key-elprat");
        filter.filter(request);
        assertEquals("api-key-client", request.getAttribute("user", String.class).orElse(""));
    }

    // --- Constructor guards ---

    @Test
    void constructorWithEmptyMap_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () -> new ApiKeyAuthFilter(Map.of()));
    }

    // --- Disabled city ---

    @Test
    void disabledCity_returns503() {
        CityFeatureFlagService disabledService = new CityFeatureFlagService(Map.of("elprat", false));
        ApiKeyAuthFilter disabledFilter = new ApiKeyAuthFilter(disabledService, Map.of("test-key-elprat", "elprat"));

        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "test-key-elprat");
        MutableHttpResponse<?> response = disabledFilter.filter(request);
        assertNotNull(response);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.getCode(), response.getStatus().getCode());
    }

    @Test
    void disabledCity_returnsCorrectErrorCode() {
        CityFeatureFlagService disabledService = new CityFeatureFlagService(Map.of("elprat", false));
        ApiKeyAuthFilter disabledFilter = new ApiKeyAuthFilter(disabledService, Map.of("test-key-elprat", "elprat"));

        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "test-key-elprat");
        MutableHttpResponse<?> response = disabledFilter.filter(request);
        assertNotNull(response);
        assertEquals(OpenRouterErrorCode.CITY_DISABLED.getCode(),
                ((Map<String, Object>) response.body()).get("errorCode"));
    }

    @Test
    void enabledCity_passesThrough() {
        CityFeatureFlagService enabledService = new CityFeatureFlagService(Map.of("elprat", true));
        ApiKeyAuthFilter enabledFilter = new ApiKeyAuthFilter(enabledService, Map.of("test-key-elprat", "elprat"));

        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "test-key-elprat");
        assertNull(enabledFilter.filter(request));
    }

    @Test
    void invalidKey_stillReturns401_evenWhenCityDisabled() {
        // Auth check (401) happens before the feature flag check (503)
        CityFeatureFlagService disabledService = new CityFeatureFlagService(Map.of("elprat", false));
        ApiKeyAuthFilter disabledFilter = new ApiKeyAuthFilter(disabledService, Map.of("test-key-elprat", "elprat"));

        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "this-key-does-not-exist");
        MutableHttpResponse<?> response = disabledFilter.filter(request);
        assertNotNull(response);
        assertEquals(HttpStatus.UNAUTHORIZED.getCode(), response.getStatus().getCode());
    }
}
