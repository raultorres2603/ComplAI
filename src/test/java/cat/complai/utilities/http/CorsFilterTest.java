package cat.complai.utilities.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CorsFilterTest {

    private static final String TEST_ORIGIN = "http://localhost:3000";

    @Test
    void handlePreflight_returnsResponseWithCorsHeadersForOptionsRequest() {
        MutableHttpRequest<?> request = HttpRequest.OPTIONS("/").header("Origin", TEST_ORIGIN);

        CorsFilter filter = new CorsFilter(TEST_ORIGIN);

        MutableHttpResponse<?> response = filter.handlePreflight(request);

        assertNotNull(response);
        assertEquals(TEST_ORIGIN,
                response.getHeaders().findFirst("Access-Control-Allow-Origin").orElse(null));
        assertEquals("GET, POST, PUT, DELETE, OPTIONS",
                response.getHeaders().findFirst("Access-Control-Allow-Methods").orElse(null));
        assertEquals("Content-Type, Authorization, X-Api-Key",
                response.getHeaders().findFirst("Access-Control-Allow-Headers").orElse(null));
        assertEquals("3600",
                response.getHeaders().findFirst("Access-Control-Max-Age").orElse(null));
    }

    @Test
    void handlePreflight_returnsNullForNonOptionsRequest() {
        MutableHttpRequest<?> request = HttpRequest.GET("/");

        CorsFilter filter = new CorsFilter(TEST_ORIGIN);

        assertNull(filter.handlePreflight(request));
    }

    @Test
    void handlePreflight_usesCustomAllowedOrigin() {
        String customOrigin = "https://example.com";
        MutableHttpRequest<?> request = HttpRequest.OPTIONS("/").header("Origin", customOrigin);

        CorsFilter filter = new CorsFilter(customOrigin);

        MutableHttpResponse<?> response = filter.handlePreflight(request);

        assertNotNull(response);
        assertEquals(customOrigin,
                response.getHeaders().findFirst("Access-Control-Allow-Origin").orElse(null));
    }

    @Test
    void addOriginHeader_addsAccessControlAllowOriginHeader() {
        MutableHttpResponse<?> response = HttpResponse.ok();
        CorsFilter filter = new CorsFilter(TEST_ORIGIN);

        filter.addOriginHeader(response);

        String header = response.getHeaders().findFirst("Access-Control-Allow-Origin").orElse(null);
        assertNotNull(header);
        assertEquals(TEST_ORIGIN, header);
    }
}
