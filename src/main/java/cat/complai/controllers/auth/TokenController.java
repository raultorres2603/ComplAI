package cat.complai.controllers.auth;

import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.utilities.auth.CityUtil;
import cat.complai.utilities.auth.SessionTokenConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.jsonwebtoken.Jwts;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * REST Controller for the session-token endpoint.
 *
 * <p>{@code POST /complai/auth/token} exchanges a client secret for a short-lived
 * HMAC-signed JWT session token. The mobile app calls this endpoint on startup
 * (or when the previous token expires) and receives a JWT that authorises all
 * subsequent API calls via the {@code Authorization: Bearer} header.
 *
 * <p>This endpoint is excluded from {@link cat.complai.utilities.auth.JwtSessionAuthFilter}
 * — it authenticates via the client secret, not a JWT.
 *
 * <p>Responses:
 * <ul>
 *   <li>200 OK: { "token": "&lt;jwt&gt;", "expiresIn": 900, "cityId": "elprat" }</li>
 *   <li>401 Unauthorized: Invalid client secret</li>
 *   <li>503 Service Unavailable: City is disabled</li>
 * </ul>
 */
@Requires(property = "api.key.enabled")
@Controller("/complai")
public class TokenController {

    private static final int DEFAULT_MAX_REQUESTS_PER_MINUTE = 10;
    private static final String RATE_LIMIT_PROPERTY = "complai.token-rate-limit.requests-per-minute";

    private final SessionTokenConfig config;
    private final Environment environment;
    private final Cache<String, AtomicInteger> rateLimitCache;
    private final Logger logger = Logger.getLogger(TokenController.class.getName());

    /**
     * Production constructor — reads rate-limit from Micronaut environment.
     */
    @Inject
    public TokenController(SessionTokenConfig config, Environment environment) {
        this.config = config;
        this.environment = environment;
        this.rateLimitCache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build();
    }

    /**
     * Test constructor — accepts a fixed rate-limit value.
     */
    TokenController(SessionTokenConfig config, int maxRequestsPerMinute) {
        this.config = config;
        this.environment = null;
        this.rateLimitCache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build();
        this.fixedRateLimit = maxRequestsPerMinute;
    }

    /** Non-null only when constructed with the test constructor. */
    private Integer fixedRateLimit;

    /**
     * Returns the current rate-limit, reading from the environment so it
     * can be overridden per-test via system properties without restarting the bean.
     */
    private int getMaxRequestsPerMinute() {
        if (fixedRateLimit != null) {
            return fixedRateLimit;
        }
        Optional<String> prop = environment.getProperty(RATE_LIMIT_PROPERTY, String.class);
        return prop.map(Integer::parseInt).orElse(DEFAULT_MAX_REQUESTS_PER_MINUTE);
    }

    /**
     * Exchanges a client secret for a session JWT.
     *
     * @param request the token request body containing {@code clientSecret} and {@code cityId}
     * @return a session JWT and its expiry, or an error response
     */
    @Post("/auth/token")
    public HttpResponse<?> token(@Body TokenRequest request, HttpRequest<?> httpRequest) {
        long start = System.currentTimeMillis();

        if (request == null || request.clientSecret() == null || request.clientSecret().isBlank()) {
            return badRequest("Missing clientSecret");
        }

        if (request.cityId() == null || request.cityId().isBlank()) {
            return badRequest("Missing cityId");
        }

        // Rate-limit by IP (token endpoint is unauthenticated)
        String clientIp = extractClientIp(httpRequest);
        AtomicInteger counter = rateLimitCache.get(clientIp, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        if (count > getMaxRequestsPerMinute()) {
            long latency = System.currentTimeMillis() - start;
            logger.warning(() -> "POST /complai/auth/token — httpStatus=429 rate limit exceeded"
                    + " ip=" + clientIp + " count=" + count + " latencyMs=" + latency);
            return HttpResponse.status(io.micronaut.http.HttpStatus.TOO_MANY_REQUESTS)
                    .body(errorBody("Rate limit exceeded. Try again later."));
        }

        String cityId = request.cityId().toLowerCase();

        // Validate client secret
        if (!config.validateClientSecret(request.clientSecret())) {
            long latency = System.currentTimeMillis() - start;
            logger.info(() -> "POST /complai/auth/token — httpStatus=401 invalid client secret"
                    + " latencyMs=" + latency);
            return HttpResponse.unauthorized().body(errorBody("Invalid client secret"));
        }

        // Check city is enabled
        if (!CityUtil.isCityEnabled(cityId)) {
            long latency = System.currentTimeMillis() - start;
            logger.info(() -> "POST /complai/auth/token — httpStatus=503 cityId=" + cityId
                    + " latencyMs=" + latency);
            return HttpResponse.status(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE)
                    .body(errorBody("City '" + cityId + "' is currently unavailable"));
        }

        // Build JWT
        Instant now = Instant.now();
        String jwt = Jwts.builder()
                .subject("mobile-app")
                .claim("city", cityId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(config.getTokenLifetimeSeconds())))
                .signWith(config.getSigningKey())
                .compact();

        long latency = System.currentTimeMillis() - start;
        logger.info(() -> "POST /complai/auth/token — httpStatus=200 cityId=" + cityId
                + " latencyMs=" + latency);

        return HttpResponse.ok(Map.of(
                "token", jwt,
                "expiresIn", config.getTokenLifetimeSeconds(),
                "cityId", cityId)).contentType(MediaType.APPLICATION_JSON);
    }

    private static Map<String, Object> errorBody(String message) {
        return Map.of(
                "success", false,
                "message", message,
                "errorCode", OpenRouterErrorCode.UNAUTHORIZED.getCode());
    }

    private static HttpResponse<?> badRequest(String message) {
        return HttpResponse.badRequest(Map.of(
                "success", false,
                "message", message,
                "errorCode", OpenRouterErrorCode.VALIDATION.getCode()));
    }

    /**
     * Extracts the client IP from the request, preferring X-Forwarded-For
     * (set by API Gateway / ALB) over the direct remote address.
     */
    private static String extractClientIp(HttpRequest<?> request) {
        String xff = request.getHeaders().get("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For may contain a comma-separated list; the first entry is the client
            String firstIp = xff.split(",")[0].trim();
            if (!firstIp.isEmpty()) {
                return firstIp;
            }
        }
        var remoteAddr = request.getRemoteAddress();
        return remoteAddr != null ? remoteAddr.getAddress().getHostAddress() : "unknown";
    }

    /**
     * Request body for the token endpoint.
     *
     * @param clientSecret the plain-text client secret from the mobile app
     * @param cityId the city identifier to include in the JWT
     */
    public record TokenRequest(String clientSecret, String cityId) {}
}
