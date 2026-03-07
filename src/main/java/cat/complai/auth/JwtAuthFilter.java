package cat.complai.auth;

import cat.complai.openrouter.dto.OpenRouterErrorCode;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.logging.Logger;

/**
 * Enforces JWT Bearer authentication on all requests.
 *
 * Excluded paths (no token required — monitoring systems must reach these without credentials):
 *   GET /
 *   GET /health
 *
 * All other requests must carry a valid "Authorization: Bearer <token>" header.
 * Returning null from a @RequestFilter method tells Micronaut to continue the filter chain.
 * Returning an HttpResponse short-circuits and sends that response immediately.
 *
 * The exclusion list is explicit here rather than in configuration so the security surface
 * is obvious and auditable in one place.
 */
@ServerFilter("/**")
public class JwtAuthFilter {

    private static final Logger logger = Logger.getLogger(JwtAuthFilter.class.getName());

    private final JwtValidator jwtValidator;

    @Inject
    public JwtAuthFilter(JwtValidator jwtValidator) {
        this.jwtValidator = jwtValidator;
    }

    /**
     * Returns null to continue the chain, or a 401 response to short-circuit.
     * @Nullable is required — Micronaut refuses a null return from @RequestFilter unless the
     * method is explicitly marked nullable (the null signals "proceed to the next filter/handler").
     */
    @RequestFilter
    @Nullable
    public MutableHttpResponse<?> filter(HttpRequest<?> request) {
        if (isExcluded(request)) {
            return null;
        }

        String authHeader = request.getHeaders().get("Authorization");
        JwtValidationResult result = jwtValidator.validate(authHeader);

        if (!result.valid()) {
            logger.fine("JWT rejected for " + request.getPath() + ": " + result.failureReason());
            return unauthorizedResponse(result.failureReason());
        }

        return null;
    }

    private boolean isExcluded(HttpRequest<?> request) {
        String path = request.getPath();
        HttpMethod method = request.getMethod();
        // Only GET / and GET /health bypass the JWT check.
        return HttpMethod.GET.equals(method) && (path.equals("/") || path.equals("/health"));
    }

    private MutableHttpResponse<?> unauthorizedResponse(String reason) {
        // Return the same shape as OpenRouterPublicDto so clients can handle 401 uniformly.
        Map<String, Object> body = Map.of(
                "success", false,
                "message", reason == null ? "Unauthorized" : reason,
                "errorCode", OpenRouterErrorCode.UNAUTHORIZED.getCode()
        );
        return HttpResponse.unauthorized().body(body);
    }
}
