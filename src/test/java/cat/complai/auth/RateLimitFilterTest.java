package cat.complai.auth;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RateLimitFilter}.
 */
class RateLimitFilterTest {

    private static MutableHttpRequest<?> postRequest(String path) {
        return HttpRequest.POST(path, "{}");
    }

    private static MutableHttpRequest<?> getRequest(String path) {
        return HttpRequest.GET(path);
    }

    // (a) Under-limit requests all pass through
    @Test
    void filter_underLimit_returnsNull() {
        RateLimitFilter filter = new RateLimitFilter(3);
        MutableHttpRequest<?> req = postRequest("/complai/ask");
        req.setAttribute(JwtAuthFilter.USER_ATTRIBUTE, "user-a");

        assertNull(filter.filter(req), "Request 1 must pass");
        assertNull(filter.filter(req), "Request 2 must pass");
        assertNull(filter.filter(req), "Request 3 must pass (at limit, not over)");
    }

    // (b) Exceeding the limit returns 429
    @Test
    void filter_exceedingLimit_returns429() {
        RateLimitFilter filter = new RateLimitFilter(3);
        MutableHttpRequest<?> req = postRequest("/complai/ask");
        req.setAttribute(JwtAuthFilter.USER_ATTRIBUTE, "user-b");

        filter.filter(req); // 1
        filter.filter(req); // 2
        filter.filter(req); // 3 — still allowed
        MutableHttpResponse<?> response = filter.filter(req); // 4 — should be blocked

        assertNotNull(response, "4th request must be blocked");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.getCode(), response.getStatus().getCode());
    }

    // (c) Excluded paths bypass the filter entirely
    @Test
    void filter_excludedPaths_returnsNull() {
        RateLimitFilter filter = new RateLimitFilter(3);

        MutableHttpRequest<?> rootReq = getRequest("/");
        assertNull(filter.filter(rootReq), "GET / must be excluded");

        MutableHttpRequest<?> healthReq = getRequest("/health");
        assertNull(filter.filter(healthReq), "GET /health must be excluded");

        MutableHttpRequest<?> startupReq = getRequest("/health/startup");
        assertNull(filter.filter(startupReq), "GET /health/startup must be excluded");
    }

    // (d) Independent user counters — two users do not interfere with each other
    @Test
    void filter_independentUserCounters_trackedSeparately() {
        RateLimitFilter filter = new RateLimitFilter(3);

        MutableHttpRequest<?> reqX = postRequest("/complai/ask");
        reqX.setAttribute(JwtAuthFilter.USER_ATTRIBUTE, "user-x");

        MutableHttpRequest<?> reqY = postRequest("/complai/ask");
        reqY.setAttribute(JwtAuthFilter.USER_ATTRIBUTE, "user-y");

        // Each user makes 3 requests — none should be blocked
        assertNull(filter.filter(reqX), "user-x request 1 must pass");
        assertNull(filter.filter(reqY), "user-y request 1 must pass");
        assertNull(filter.filter(reqX), "user-x request 2 must pass");
        assertNull(filter.filter(reqY), "user-y request 2 must pass");
        assertNull(filter.filter(reqX), "user-x request 3 must pass");
        assertNull(filter.filter(reqY), "user-y request 3 must pass");

        // 4th request for each user is blocked independently
        MutableHttpResponse<?> blockedX = filter.filter(reqX);
        assertNotNull(blockedX, "user-x 4th request must be blocked");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.getCode(), blockedX.getStatus().getCode());

        MutableHttpResponse<?> blockedY = filter.filter(reqY);
        assertNotNull(blockedY, "user-y 4th request must be blocked independently");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.getCode(), blockedY.getStatus().getCode());
    }
}
