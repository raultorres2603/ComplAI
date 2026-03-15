package cat.complai.http;

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

/**
 * Adds CORS headers to every response and short-circuits OPTIONS preflight requests
 * with a 200 before the JWT filter runs.
 *
 * <p>In production, Lambda Function URL already injects CORS headers at the AWS
 * infrastructure level (see CDK lambda-stack.ts addFunctionUrl cors config).
 * This filter covers the same concern locally (SAM local + browser) where no
 * infrastructure layer is present to add those headers. Having both active is safe:
 * Lambda Function URL skips its own CORS headers when the function response already
 * contains {@code Access-Control-Allow-Origin}.
 *
 * <p>Priority must be {@code HIGHEST_PRECEDENCE} so this filter runs before
 * {@code JwtAuthFilter}. Without that, OPTIONS preflight requests (which browsers
 * send without an Authorization header) would be rejected with 401 before the
 * CORS response can be returned.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@ServerFilter("/**")
public class CorsFilter {

    @RequestFilter
    @Nullable
    public MutableHttpResponse<?> handlePreflight(MutableHttpRequest<?> request) {
        if (!HttpMethod.OPTIONS.equals(request.getMethod())) {
            return null;
        }
        return HttpResponse.ok()
                .header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                .header("Access-Control-Allow-Headers", "Content-Type, Authorization")
                .header("Access-Control-Max-Age", "3600");
    }

    @ResponseFilter
    public void addOriginHeader(MutableHttpResponse<?> response) {
        response.header("Access-Control-Allow-Origin", "*");
    }
}

