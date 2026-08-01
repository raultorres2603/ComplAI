package cat.complai.utilities.auth;

import cat.complai.dto.openrouter.OpenRouterErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Enforces JWT session-token authentication on all requests.
 *
 * <p>Replaces the former {@code ApiKeyAuthFilter}. Reads the
 * {@code Authorization: Bearer <token>} header, validates the HMAC-SHA256
 * signature and expiry, extracts the {@code city} claim, and sets the
 * {@link AuthConstants#CITY_ATTRIBUTE} and {@link AuthConstants#USER_ATTRIBUTE}
 * request attributes for downstream controllers.
 *
 * <p>Excluded paths (no token required): {@code GET /}, {@code GET /health},
 * {@code GET /health/startup}, {@code GET /privacy},
 * {@code POST /complai/auth/token}, {@code /telegram/**}.
 *
 * <p>Returning {@code null} from a {@code @RequestFilter} method tells
 * Micronaut to continue the filter chain. Returning a response
 * short-circuits immediately.
 */
@Requires(property = "api.key.enabled")
@ServerFilter("/**")
public class JwtSessionAuthFilter {

    private static final Logger logger = Logger.getLogger(JwtSessionAuthFilter.class.getName());

    private final JwtParser jwtParser;

    public JwtSessionAuthFilter(SessionTokenConfig config) {
        this.jwtParser = Jwts.parser()
                .verifyWith(config.getSigningKey())
                .build();
    }

    // Visible for unit tests — accepts a pre-built parser
    JwtSessionAuthFilter(JwtParser jwtParser) {
        this.jwtParser = jwtParser;
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
        if (authHeader == null || authHeader.isBlank()) {
            logger.warning(() -> "Missing Authorization header — httpStatus=401 method=" + request.getMethod()
                    + " path=" + request.getPath());
            return unauthorizedResponse("Missing Authorization header");
        }

        if (!authHeader.startsWith("Bearer ")) {
            logger.warning(() -> "Malformed Authorization header — httpStatus=401 method=" + request.getMethod()
                    + " path=" + request.getPath());
            return unauthorizedResponse("Invalid Authorization header format");
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            return unauthorizedResponse("Empty Bearer token");
        }

        Claims claims;
        try {
            claims = jwtParser.parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            logger.info(() -> "Token expired — httpStatus=401 method=" + request.getMethod()
                    + " path=" + request.getPath());
            return unauthorizedResponse("Token expired");
        } catch (SignatureException e) {
            logger.warning(() -> "Invalid token signature — httpStatus=401 method=" + request.getMethod()
                    + " path=" + request.getPath());
            return unauthorizedResponse("Invalid token");
        } catch (JwtException e) {
            logger.warning(() -> "Invalid JWT — httpStatus=401 method=" + request.getMethod()
                    + " path=" + request.getPath() + " error=" + e.getMessage());
            return unauthorizedResponse("Invalid token");
        }

        String cityId = claims.get("city", String.class);
        if (cityId == null || cityId.isBlank()) {
            logger.warning(() -> "Token missing 'city' claim — httpStatus=401 method=" + request.getMethod()
                    + " path=" + request.getPath());
            return unauthorizedResponse("Invalid token");
        }

        if (!CityUtil.isCityEnabled(cityId)) {
            logger.info(() -> "City disabled — cityId=" + cityId + " httpStatus=503 method=" + request.getMethod()
                    + " path=" + request.getPath());
            return cityDisabledResponse(cityId);
        }

        request.setAttribute(AuthConstants.CITY_ATTRIBUTE, cityId);
        request.setAttribute(AuthConstants.USER_ATTRIBUTE, "jwt-session");

        return null;
    }

    private static boolean isExcluded(HttpRequest<?> request) {
        String path = request.getPath();
        HttpMethod method = request.getMethod();

        // Exclude Telegram webhook paths (Telegram cannot send custom headers)
        if (path != null && path.startsWith("/telegram/")) {
            return true;
        }

        // Exclude token endpoint (must be callable without a JWT)
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
                "message", reason == null ? "Unauthorized" : reason,
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
}
