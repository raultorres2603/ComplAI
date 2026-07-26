package cat.complai.utilities.auth;

import cat.complai.dto.openrouter.OpenRouterErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Singleton;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Set;

/**
 * Test replacement for {@link JwtSessionAuthFilter}.
 *
 * <p>Annotated with {@code @Replaces(JwtSessionAuthFilter.class)} so Micronaut
 * swaps this in for all {@code @MicronautTest} classes. No {@code @Requires}
 * guard — loads in all test environments.
 *
 * <p>Uses a hardcoded test JWT signing key. Tests create valid tokens via
 * {@link #createTestToken(String)} or {@link #createExpiredTestToken(String)}.
 */
@Singleton
@ServerFilter("/**")
@Replaces(JwtSessionAuthFilter.class)
public class TestJwtSessionFilter {

    private static final String TEST_JWT_SECRET = Base64.getEncoder().encodeToString(
            "test-jwt-secret-at-least-256-bits-long-for-hmac-sha256".getBytes(StandardCharsets.UTF_8));
    private static final SecretKey TEST_KEY = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_JWT_SECRET));

    private final JwtParser jwtParser;
    private final Set<String> disabledCities;

    public TestJwtSessionFilter() {
        this.jwtParser = Jwts.parser().verifyWith(TEST_KEY).build();
        this.disabledCities = java.util.stream.Stream.of("elprat", "testcity")
                .filter(cityId -> {
                    String enabled = System.getenv().getOrDefault("ENABLE_CITY_" + cityId.toUpperCase(), "true");
                    return "false".equalsIgnoreCase(enabled);
                })
                .collect(java.util.stream.Collectors.toSet());
    }

    // Visible for tests — accepts a custom disabled cities set
    public TestJwtSessionFilter(Set<String> disabledCities) {
        this.jwtParser = Jwts.parser().verifyWith(TEST_KEY).build();
        this.disabledCities = Set.copyOf(disabledCities);
    }

    @RequestFilter
    @Nullable
    public MutableHttpResponse<?> filter(MutableHttpRequest<?> request) {
        if (isExcluded(request)) {
            return null;
        }

        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return null;
        }

        String authHeader = request.getHeaders().get("Authorization");
        if (authHeader == null || authHeader.isBlank() || !authHeader.startsWith("Bearer ")) {
            return unauthorizedResponse("Missing or invalid Authorization header");
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            return unauthorizedResponse("Empty Bearer token");
        }

        Claims claims;
        try {
            claims = jwtParser.parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return unauthorizedResponse("Invalid token");
        }

        String cityId = claims.get("city", String.class);
        if (cityId == null || cityId.isBlank()) {
            return unauthorizedResponse("Invalid token");
        }

        if (disabledCities.contains(cityId)) {
            return cityDisabledResponse(cityId);
        }

        request.setAttribute(AuthConstants.CITY_ATTRIBUTE, cityId);
        request.setAttribute(AuthConstants.USER_ATTRIBUTE, "jwt-session");

        return null;
    }

    private static boolean isExcluded(HttpRequest<?> request) {
        String path = request.getPath();
        HttpMethod method = request.getMethod();
        if (path != null && path.startsWith("/telegram/")) {
            return true;
        }
        if (path != null && path.equals("/complai/auth/token")) {
            return true;
        }
        return HttpMethod.GET.equals(method)
                && (path.equals("/") || path.equals("/health") || path.equals("/health/startup")
                        || path.equals("/privacy"));
    }

    private MutableHttpResponse<?> unauthorizedResponse(String reason) {
        Map<String, Object> body = Map.of(
                "success", false,
                "message", reason,
                "errorCode", OpenRouterErrorCode.UNAUTHORIZED.getCode());
        return HttpResponse.unauthorized().body(body);
    }

    private MutableHttpResponse<?> cityDisabledResponse(String cityId) {
        Map<String, Object> body = Map.of(
                "success", false,
                "message", "City '" + cityId + "' is currently unavailable",
                "errorCode", OpenRouterErrorCode.CITY_DISABLED.getCode());
        return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * Creates a valid test JWT for the given city, with 1-hour expiry.
     */
    public static String createTestToken(String cityId) {
        return Jwts.builder()
                .subject("test-client")
                .claim("city", cityId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(TEST_KEY)
                .compact();
    }

    /**
     * Creates an expired test JWT for the given city.
     */
    public static String createExpiredTestToken(String cityId) {
        return Jwts.builder()
                .subject("test-client")
                .claim("city", cityId)
                .issuedAt(new Date(System.currentTimeMillis() - 7200_000))
                .expiration(new Date(System.currentTimeMillis() - 3600_000))
                .signWith(TEST_KEY)
                .compact();
    }
}
