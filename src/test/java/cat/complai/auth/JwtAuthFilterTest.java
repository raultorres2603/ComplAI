package cat.complai.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests JwtAuthFilter in isolation by calling filter() directly and inspecting the return value.
 * null  → request is allowed through (chain continues).
 * non-null → request is blocked (the returned response is sent immediately).
 */
class JwtAuthFilterTest {

    private static final String VALID_SECRET_B64 = "hEmatrRKbxfC/9PxZ14VsYksRkTZHMpqRScBUhshYzQ=";
    private static final String ISSUER = "complai";

    private SecretKey key;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        byte[] keyBytes = Base64.getDecoder().decode(VALID_SECRET_B64);
        key = Keys.hmacShaKeyFor(keyBytes);
        filter = new JwtAuthFilter(new JwtValidator(key));
    }

    // --- Excluded paths bypass JWT entirely ---

    @Test
    void getRoot_noToken_returnsNull() {
        MutableHttpRequest<?> request = HttpRequest.GET("/");
        assertNull(filter.filter(request), "GET / must pass through without a token");
    }

    @Test
    void getHealth_noToken_returnsNull() {
        MutableHttpRequest<?> request = HttpRequest.GET("/health");
        assertNull(filter.filter(request), "GET /health must pass through without a token");
    }

    // POST to / is NOT excluded — it requires a token.
    @Test
    void postRoot_noToken_returns401() {
        MutableHttpRequest<?> request = HttpRequest.POST("/", "{}");
        MutableHttpResponse<?> response = filter.filter(request);
        assertNotNull(response);
        assertEquals(401, response.getStatus().getCode());
    }

    // --- Missing / malformed Authorization header ---

    @Test
    void missingAuthHeader_returns401() {
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}");
        MutableHttpResponse<?> response = filter.filter(request);
        assertNotNull(response);
        assertEquals(401, response.getStatus().getCode());
    }

    @Test
    void authHeaderWithoutBearerPrefix_returns401() {
        // Raw token string with no "Bearer " prefix.
        String rawToken = buildToken("citizen-app", ISSUER, futureDate(30));
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("Authorization", rawToken);
        MutableHttpResponse<?> response = filter.filter(request);
        assertNotNull(response);
        assertEquals(401, response.getStatus().getCode());
    }

    // --- Valid token passes through ---

    @Test
    void validToken_returnsNull() {
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("Authorization", "Bearer " + buildToken("citizen-app", ISSUER, futureDate(30)));
        assertNull(filter.filter(request), "Valid token must pass through (return null)");
    }

    // --- Invalid tokens are blocked ---

    @Test
    void expiredToken_returns401() {
        Date past = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("Authorization", "Bearer " + buildToken("citizen-app", ISSUER, past));
        MutableHttpResponse<?> response = filter.filter(request);
        assertNotNull(response);
        assertEquals(401, response.getStatus().getCode());
    }

    @Test
    void tokenWithWrongIssuer_returns401() {
        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("Authorization", "Bearer " + buildToken("citizen-app", "other-system", futureDate(30)));
        MutableHttpResponse<?> response = filter.filter(request);
        assertNotNull(response);
        assertEquals(401, response.getStatus().getCode());
    }

    @Test
    void tokenSignedWithDifferentKey_returns401() {
        byte[] otherBytes = Base64.getDecoder().decode("dGVzdC1zZWNyZXQtdGhhdC1pcy1leGFjdGx5LTMyLWJ5dGVzLWxvbmcx");
        SecretKey otherKey = Keys.hmacShaKeyFor(otherBytes);
        String token = Jwts.builder()
                .subject("citizen-app")
                .issuer(ISSUER)
                .issuedAt(new Date())
                .expiration(futureDate(30))
                .signWith(otherKey)
                .compact();

        MutableHttpRequest<?> request = HttpRequest.POST("/complai/ask", "{}")
                .header("Authorization", "Bearer " + token);
        MutableHttpResponse<?> response = filter.filter(request);
        assertNotNull(response);
        assertEquals(401, response.getStatus().getCode());
    }

    // --- Helpers ---

    private String buildToken(String subject, String issuer, Date expiry) {
        return Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .issuedAt(new Date())
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    private Date futureDate(int days) {
        return Date.from(Instant.now().plus(days, ChronoUnit.DAYS));
    }
}
