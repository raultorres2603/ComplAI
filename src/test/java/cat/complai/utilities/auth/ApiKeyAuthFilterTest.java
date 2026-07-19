package cat.complai.utilities.auth;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

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

    // --- City enable/disable ---

    @Test
    void disabledCity_returns503() {
        ApiKeyAuthFilter disabledFilter = new ApiKeyAuthFilter(
                Map.of("test-key-elprat", "elprat"), Set.of("elprat"));
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "test-key-elprat");
        MutableHttpResponse<?> response = disabledFilter.filter(request);
        assertNotNull(response, "Disabled city must return a response (not null)");
        assertEquals(503, response.getStatus().getCode());
    }

    @Test
    void disabledCity_returnsCityDisabledErrorCode() {
        ApiKeyAuthFilter disabledFilter = new ApiKeyAuthFilter(
                Map.of("test-key-elprat", "elprat"), Set.of("elprat"));
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "test-key-elprat");
        MutableHttpResponse<?> response = disabledFilter.filter(request);
        assertNotNull(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.body();
        assertEquals(9, body.get("errorCode"), "Must return CITY_DISABLED error code (9)");
        assertFalse((Boolean) body.get("success"));
    }

    @Test
    void disabledCity_doesNotSetAttributes() {
        ApiKeyAuthFilter disabledFilter = new ApiKeyAuthFilter(
                Map.of("test-key-elprat", "elprat"), Set.of("elprat"));
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "test-key-elprat");
        disabledFilter.filter(request);
        assertFalse(request.getAttribute("city", String.class).isPresent(),
                "Disabled city must not set city attribute");
    }

    @Test
    void enabledCity_returnsNull() {
        ApiKeyAuthFilter enabledFilter = new ApiKeyAuthFilter(
                Map.of("test-key-elprat", "elprat"), Set.of());
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "test-key-elprat");
        assertNull(enabledFilter.filter(request), "Enabled city must pass through (return null)");
    }

    @Test
    void enabledCity_setsAttributes() {
        ApiKeyAuthFilter enabledFilter = new ApiKeyAuthFilter(
                Map.of("test-key-elprat", "elprat"), Set.of());
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "test-key-elprat");
        enabledFilter.filter(request);
        assertEquals("elprat", request.getAttribute("city", String.class).orElse(""));
        assertEquals("api-key-client", request.getAttribute("user", String.class).orElse(""));
    }

    @Test
    void getHealth_disabledCity_returnsNull() {
        ApiKeyAuthFilter disabledFilter = new ApiKeyAuthFilter(
                Map.of("test-key-elprat", "elprat"), Set.of("elprat"));
        MutableHttpRequest<?> request = HttpRequest.GET("/health");
        assertNull(disabledFilter.filter(request),
                "GET /health must pass through even for disabled cities");
    }

    @Test
    void getPrivacy_disabledCity_returnsNull() {
        ApiKeyAuthFilter disabledFilter = new ApiKeyAuthFilter(
                Map.of("test-key-elprat", "elprat"), Set.of("elprat"));
        MutableHttpRequest<?> request = HttpRequest.GET("/privacy");
        assertNull(disabledFilter.filter(request),
                "GET /privacy must pass through even for disabled cities");
    }

    @Test
    void telegramWebhook_disabledCity_returnsNull() {
        ApiKeyAuthFilter disabledFilter = new ApiKeyAuthFilter(
                Map.of("test-key-elprat", "elprat"), Set.of("elprat"));
        MutableHttpRequest<?> request = HttpRequest.POST("/telegram/webhook/elprat", "{}");
        assertNull(disabledFilter.filter(request),
                "Telegram webhooks must pass through (excluded from auth filter)");
    }

    @Test
    void multiCityFilter_oneDisabledOneEnabled() {
        ApiKeyAuthFilter mixedFilter = new ApiKeyAuthFilter(
                Map.of("key-a", "citya", "key-b", "cityb"),
                Set.of("citya"));

        // Disabled city returns 503
        MutableHttpRequest<?> requestA = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "key-a");
        MutableHttpResponse<?> responseA = mixedFilter.filter(requestA);
        assertNotNull(responseA);
        assertEquals(503, responseA.getStatus().getCode());

        // Enabled city passes through
        MutableHttpRequest<?> requestB = HttpRequest.POST("/complai/ask", "{}")
                .header("X-Api-Key", "key-b");
        assertNull(mixedFilter.filter(requestB), "Enabled city must pass through");
    }
}
