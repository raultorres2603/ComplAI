package cat.complai.utilities.http;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import java.util.Arrays;
import java.util.Set;
import java.util.HashSet;

/**
 * Adds CORS headers to every response and short-circuits OPTIONS preflight
 * requests
 * with a 200 before the JWT filter runs.
 *
 * <p>
 * In production, Lambda Function URL already injects CORS headers at the AWS
 * infrastructure level (see CDK lambda-stack.ts addFunctionUrl cors config).
 * This filter covers the same concern locally (SAM local + browser) where no
 * infrastructure layer is present to add those headers.
 *
 * <p>
 * This filter is disabled in production (Lambda) by setting
 * COMPLAI_LOCAL_CORS_ENABLED=false. In local development (SAM), it defaults to
 * enabled.
 * Disabling this filter in production prevents duplicate CORS headers that
 * would conflict
 * with the Lambda Function URL's CORS configuration.
 *
 * <p>
 * Priority must be {@code HIGHEST_PRECEDENCE} so this filter runs before
 * {@code JwtAuthFilter}. Without that, OPTIONS preflight requests (which
 * browsers
 * send without an Authorization header) would be rejected with 401 before the
 * CORS response can be returned.
 *
 * <p>
 * Supports comma-separated list of allowed origins for mobile app development.
 * Example: COMPLAI_CORS_ALLOWED_ORIGIN=https://raultorres2603.github.io,exp://192.168.1.100:8081
 */
@Requires(property = "complai.local-cors-enabled", value = "true")
@Order(Ordered.HIGHEST_PRECEDENCE)
@ServerFilter("/**")
public class CorsFilter {

    private final Set<String> allowedOrigins;

    public CorsFilter(@Value("${complai.cors.allowed-origin:https://raultorres2603.github.io}") String allowedOrigin) {
        // Support comma-separated list of origins
        this.allowedOrigins = new HashSet<>(Arrays.asList(allowedOrigin.split(",")));
    }

    @RequestFilter
    @Nullable
    public MutableHttpResponse<?> handlePreflight(MutableHttpRequest<?> request) {
        if (!HttpMethod.OPTIONS.equals(request.getMethod())) {
            return null;
        }

        String origin = request.getHeaders().get("Origin");
        String allowedOriginForRequest = getAllowedOrigin(origin);

        return HttpResponse.ok()
                .header("Access-Control-Allow-Origin", allowedOriginForRequest)
                .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization")
                .header("Access-Control-Max-Age", "3600");
    }

    @ResponseFilter
    public void addOriginHeader(MutableHttpResponse<?> response) {
        // For response filter, we use the first allowed origin or wildcard
        // In practice, the request filter handles the origin matching
        if (!allowedOrigins.isEmpty()) {
            response.header("Access-Control-Allow-Origin", allowedOrigins.iterator().next());
        }
    }

    private String getAllowedOrigin(String requestOrigin) {
        if (requestOrigin == null) {
            return allowedOrigins.isEmpty() ? "*" : allowedOrigins.iterator().next();
        }

        // Check if the request origin is in the allowed list
        if (allowedOrigins.contains(requestOrigin)) {
            return requestOrigin;
        }

        // For mobile apps using exp:// protocol, allow them
        if (requestOrigin.startsWith("exp://") || requestOrigin.startsWith("http://localhost")) {
            return requestOrigin;
        }

        // Default to first allowed origin
        return allowedOrigins.isEmpty() ? "*" : allowedOrigins.iterator().next();
    }
}
