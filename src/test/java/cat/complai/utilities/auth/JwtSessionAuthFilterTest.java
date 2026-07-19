package cat.complai.utilities.auth;

import cat.complai.dto.openrouter.OpenRouterErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

class JwtSessionAuthFilterTest {

    // 256-bit key for tests
    private static final String TEST_SECRET = Base64.getEncoder().encodeToString(
            "test-jwt-secret-at-least-256-bits-long-for-hmac".getBytes(StandardCharsets.UTF_8));
    private static final SecretKey TEST_KEY = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_SECRET));
    private static final JwtParser TEST_PARSER = Jwts.parser().verifyWith(TEST_KEY).build();

    private JwtSessionAuthFilter filter;

    @BeforeEach
    void setUp() {
        // Ensure no test pollution from other classes that set ENABLE_CITY_* system properties
        System.clearProperty("ENABLE_CITY_ELPRAT");
        System.clearProperty("ENABLE_CITY_TESTCITY");
        filter = new JwtSessionAuthFilter(TEST_PARSER);
    }

    private static String createValidToken(String cityId) {
        return Jwts.builder()
                .subject("mobile-app")
                .claim("city", cityId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(TEST_KEY)
                .compact();
    }

    private static String createExpiredToken(String cityId) {
        return Jwts.builder()
                .subject("mobile-app")
                .claim("city", cityId)
                .issuedAt(new Date(System.currentTimeMillis() - 7200_000))
                .expiration(new Date(System.currentTimeMillis() - 3600_000))
                .signWith(TEST_KEY)
                .compact();
    }

    private static String createWrongKeyToken(String cityId) {
        SecretKey wrongKey = Keys.hmacShaKeyFor("wrong-key-at-least-256-bits-long-for-hmac-testing!!".getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject("mobile-app")
                .claim("city", cityId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(wrongKey)
                .compact();
    }

    private static MutableHttpRequest<?> requestWithAuth(String token) {
        MutableHttpRequest<?> req = HttpRequest.POST("/complai/ask", "");
        req.getHeaders().add("Authorization", "Bearer " + token);
        return req;
    }

    private static MutableHttpRequest<?> requestWithoutAuth() {
        return HttpRequest.POST("/complai/ask", "");
    }

    private static MutableHttpRequest<?> excludedRequest(String path, HttpMethod method) {
        if (method == HttpMethod.GET) {
            return HttpRequest.GET(path);
        }
        return HttpRequest.POST(path, "");
    }

    @Nested
    @DisplayName("Valid token")
    class ValidToken {

        @Test
        @DisplayName("sets city attribute and continues chain")
        void filter_validToken_setsCityAttribute() {
            try (MockedStatic<CityUtil> cityUtilMock = mockStatic(CityUtil.class)) {
                cityUtilMock.when(() -> CityUtil.isCityEnabled("elprat")).thenReturn(true);

                String token = createValidToken("elprat");
                MutableHttpRequest<?> request = requestWithAuth(token);

                MutableHttpResponse<?> result = filter.filter(request);

                assertNull(result, "Should return null to continue filter chain");
                assertEquals("elprat", request.getAttribute(AuthConstants.CITY_ATTRIBUTE, String.class).orElse(null));
                assertEquals("jwt-session", request.getAttribute(AuthConstants.USER_ATTRIBUTE, String.class).orElse(null));
            }
        }

        @Test
        @DisplayName("valid token + disabled city returns 503")
        void filter_validTokenDisabledCity_returns503() {
            String token = createValidToken("elprat");
            MutableHttpRequest<?> request = requestWithAuth(token);

            MutableHttpResponse<?> result = filter.filter(request);

            assertNotNull(result);
            assertEquals(503, result.getStatus().getCode());
        }
    }

    @Nested
    @DisplayName("Missing/invalid Authorization header")
    class MissingAuth {

        @Test
        @DisplayName("missing header returns 401")
        void filter_missingHeader_returns401() {
            MutableHttpRequest<?> request = requestWithoutAuth();
            MutableHttpResponse<?> result = filter.filter(request);

            assertNotNull(result);
            assertEquals(401, result.getStatus().getCode());
        }

        @Test
        @DisplayName("malformed header (no Bearer prefix) returns 401")
        void filter_malformedHeader_returns401() {
            MutableHttpRequest<?> req = HttpRequest.POST("/complai/ask", "");
            req.getHeaders().add("Authorization", "Basic abc123");
            MutableHttpResponse<?> result = filter.filter(req);

            assertNotNull(result);
            assertEquals(401, result.getStatus().getCode());
        }

        @Test
        @DisplayName("empty Bearer token returns 401")
        void filter_emptyBearerToken_returns401() {
            MutableHttpRequest<?> req = HttpRequest.POST("/complai/ask", "");
            req.getHeaders().add("Authorization", "Bearer ");
            MutableHttpResponse<?> result = filter.filter(req);

            assertNotNull(result);
            assertEquals(401, result.getStatus().getCode());
        }
    }

    @Nested
    @DisplayName("Token validation errors")
    class TokenErrors {

        @Test
        @DisplayName("expired token returns 401 with 'Token expired'")
        void filter_expiredToken_returns401() {
            String token = createExpiredToken("elprat");
            MutableHttpRequest<?> request = requestWithAuth(token);
            MutableHttpResponse<?> result = filter.filter(request);

            assertNotNull(result);
            assertEquals(401, result.getStatus().getCode());
            Map<?, ?> body = (Map<?, ?>) result.body();
            assertEquals("Token expired", body.get("message"));
        }

        @Test
        @DisplayName("wrong signing key returns 401")
        void filter_wrongKey_returns401() {
            String token = createWrongKeyToken("elprat");
            MutableHttpRequest<?> request = requestWithAuth(token);
            MutableHttpResponse<?> result = filter.filter(request);

            assertNotNull(result);
            assertEquals(401, result.getStatus().getCode());
        }

        @Test
        @DisplayName("token missing 'city' claim returns 401")
        void filter_missingCityClaim_returns401() {
            String token = Jwts.builder()
                    .subject("mobile-app")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 3600_000))
                    .signWith(TEST_KEY)
                    .compact();
            MutableHttpRequest<?> request = requestWithAuth(token);
            MutableHttpResponse<?> result = filter.filter(request);

            assertNotNull(result);
            assertEquals(401, result.getStatus().getCode());
        }
    }

    @Nested
    @DisplayName("Excluded paths")
    class ExcludedPaths {

        @Test
        @DisplayName("GET /health passes through")
        void filter_healthEndpoint_passesThrough() {
            MutableHttpResponse<?> result = filter.filter(excludedRequest("/health", HttpMethod.GET));
            assertNull(result);
        }

        @Test
        @DisplayName("GET /health/startup passes through")
        void filter_healthStartupEndpoint_passesThrough() {
            MutableHttpResponse<?> result = filter.filter(excludedRequest("/health/startup", HttpMethod.GET));
            assertNull(result);
        }

        @Test
        @DisplayName("GET /privacy passes through")
        void filter_privacyEndpoint_passesThrough() {
            MutableHttpResponse<?> result = filter.filter(excludedRequest("/privacy", HttpMethod.GET));
            assertNull(result);
        }

        @Test
        @DisplayName("POST /complai/auth/token passes through")
        void filter_tokenEndpoint_passesThrough() {
            MutableHttpResponse<?> result = filter.filter(excludedRequest("/complai/auth/token", HttpMethod.POST));
            assertNull(result);
        }

        @Test
        @DisplayName("POST /telegram/webhook/elprat passes through")
        void filter_telegramWebhook_passesThrough() {
            MutableHttpResponse<?> result = filter.filter(excludedRequest("/telegram/webhook/elprat", HttpMethod.POST));
            assertNull(result);
        }

        @Test
        @DisplayName("OPTIONS method passes through")
        void filter_optionsMethod_passesThrough() {
            MutableHttpRequest<?> req = HttpRequest.OPTIONS("/complai/ask");
            MutableHttpResponse<?> result = filter.filter(req);
            assertNull(result);
        }
    }
}
