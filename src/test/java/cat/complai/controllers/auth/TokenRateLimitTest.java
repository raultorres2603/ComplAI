package cat.complai.controllers.auth;

import cat.complai.utilities.auth.SessionTokenConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for the per-IP rate-limit on {@code POST /complai/auth/token}.
 * Directly instantiates {@link TokenController} with a low rate-limit (3 req/min).
 */
class TokenRateLimitTest {

    private static final String VALID_CLIENT_SECRET = "test-client-secret";

    private static final SecretKey SIGNING_KEY = Keys.hmacShaKeyFor(
            Base64.getDecoder().decode(
                    "dGVzdC1qd3Qtc2VjcmV0LWF0LWxlYXN0LTI1Ni1iaXRzLWxvbmctZm9yLWhtYWMtc2hhMjU2"));

    private TokenController controller;

    @BeforeEach
    void setUp() {
        System.setProperty("ENABLE_CITY_ELPRAT", "true");

        SessionTokenConfig config = new SessionTokenConfig(
                "dGVzdC1qd3Qtc2VjcmV0LWF0LWxlYXN0LTI1Ni1iaXRzLWxvbmctZm9yLWhtYWMtc2hhMjU2",
                "8ac950188678f9bb3524b275130332b511bf5092394da6975b5fb9e84302f026",
                900);

        controller = new TokenController(config, 3);
    }

    private TokenController.TokenRequest validRequest() {
        return new TokenController.TokenRequest(VALID_CLIENT_SECRET, "elprat");
    }

    private HttpRequest<?> requestWithIp(String ip) {
        io.micronaut.http.MutableHttpRequest<?> req = HttpRequest.POST("/complai/auth/token", validRequest());
        req.getHeaders().add("X-Forwarded-For", ip);
        return req;
    }

    @Test
    @DisplayName("returns 200 within rate limit")
    void withinLimit_returns200() {
        HttpResponse<?> resp = controller.token(validRequest(), requestWithIp("10.0.0.1"));
        assertEquals(200, resp.getStatus().getCode());
    }

    @Test
    @DisplayName("returns 429 when rate limit exceeded")
    void rateLimitExceeded_returns429() {
        String ip = "10.0.0.2";
        // Exhaust the limit (3 requests)
        controller.token(validRequest(), requestWithIp(ip)); // count=1
        controller.token(validRequest(), requestWithIp(ip)); // count=2
        controller.token(validRequest(), requestWithIp(ip)); // count=3

        // 4th request should be rate-limited
        HttpResponse<?> resp = controller.token(validRequest(), requestWithIp(ip));
        assertEquals(429, resp.getStatus().getCode());
    }

    @Test
    @DisplayName("different IPs have separate rate limits")
    void differentIps_separateLimits() {
        // Exhaust IP 1
        controller.token(validRequest(), requestWithIp("10.0.0.3"));
        controller.token(validRequest(), requestWithIp("10.0.0.3"));
        controller.token(validRequest(), requestWithIp("10.0.0.3"));

        // IP 1 should be rate-limited
        HttpResponse<?> resp1 = controller.token(validRequest(), requestWithIp("10.0.0.3"));
        assertEquals(429, resp1.getStatus().getCode());

        // IP 2 should still work
        HttpResponse<?> resp2 = controller.token(validRequest(), requestWithIp("10.0.0.4"));
        assertEquals(200, resp2.getStatus().getCode());
    }

    @Test
    @DisplayName("returns 401 for invalid secret even within rate limit")
    void invalidSecret_returns401() {
        TokenController.TokenRequest badReq = new TokenController.TokenRequest("wrong-secret", "elprat");
        HttpResponse<?> resp = controller.token(badReq, requestWithIp("10.0.0.5"));
        assertEquals(401, resp.getStatus().getCode());
    }

    @Test
    @DisplayName("returns 503 for disabled city")
    void disabledCity_returns503() {
        TokenController.TokenRequest req = new TokenController.TokenRequest(VALID_CLIENT_SECRET, "testcity");
        HttpResponse<?> resp = controller.token(req, requestWithIp("10.0.0.6"));
        assertEquals(503, resp.getStatus().getCode());
    }
}
