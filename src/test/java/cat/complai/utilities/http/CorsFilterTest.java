package cat.complai.utilities.http;

import io.micronaut.http.HttpMethod;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.MutableHttpHeaders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorsFilterTest {

    private static final String TEST_ORIGIN = "http://localhost:3000";

    /**
     * Creates a mock request with the given method and an empty (but non-null)
     * {@link HttpHeaders} so that {@code request.getHeaders().get("Origin")}
     * returns {@code null} instead of throwing {@link NullPointerException}.
     */
    private static MutableHttpRequest<?> mockRequest(HttpMethod method) {
        MutableHttpRequest<?> request = mock(MutableHttpRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getHeaders()).thenReturn(mock(MutableHttpHeaders.class));
        return request;
    }

    @Test
    void handlePreflight_returnsResponseWithCorsHeadersForOptionsRequest() {
        MutableHttpRequest<?> request = mockRequest(HttpMethod.OPTIONS);

        CorsFilter filter = new CorsFilter(TEST_ORIGIN);

        MutableHttpResponse<?> response = filter.handlePreflight(request);

        assertNotNull(response);
        assertEquals(TEST_ORIGIN,
                response.getHeaders().findFirst("Access-Control-Allow-Origin").orElse(null));
        assertEquals("GET, POST, PUT, DELETE, OPTIONS",
                response.getHeaders().findFirst("Access-Control-Allow-Methods").orElse(null));
        assertEquals("Content-Type, Authorization",
                response.getHeaders().findFirst("Access-Control-Allow-Headers").orElse(null));
        assertEquals("3600",
                response.getHeaders().findFirst("Access-Control-Max-Age").orElse(null));
    }

    @Test
    void handlePreflight_returnsNullForNonOptionsRequest() {
        MutableHttpRequest<?> request = mockRequest(HttpMethod.GET);

        CorsFilter filter = new CorsFilter(TEST_ORIGIN);

        assertNull(filter.handlePreflight(request));
    }

    @Test
    void handlePreflight_usesCustomAllowedOrigin() {
        String customOrigin = "https://example.com";
        MutableHttpRequest<?> request = mockRequest(HttpMethod.OPTIONS);

        CorsFilter filter = new CorsFilter(customOrigin);

        MutableHttpResponse<?> response = filter.handlePreflight(request);

        assertNotNull(response);
        assertEquals(customOrigin,
                response.getHeaders().findFirst("Access-Control-Allow-Origin").orElse(null));
    }

    @Test
    void addOriginHeader_addsAccessControlAllowOriginHeader() {
        MutableHttpResponse<?> response = mock(MutableHttpResponse.class);
        CorsFilter filter = new CorsFilter(TEST_ORIGIN);

        filter.addOriginHeader(response);

        verify(response).header("Access-Control-Allow-Origin", TEST_ORIGIN);
    }
}
