package cat.complai.auth;

import cat.complai.openrouter.dto.OpenRouterErrorCode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Per-user in-process rate limiter using Caffeine.
 *
 * <p>
 * Counts requests per authenticated user in a 1-minute sliding window.
 * Returns HTTP 429 when the limit is exceeded.
 *
 * <p>
 * Must run AFTER {@link JwtAuthFilter} so the {@code user} attribute is
 * already set on the request. This is achieved by implementing
 * {@link Ordered} and returning a numerically higher order value than the
 * JwtAuthFilter default (0), so this filter executes second.
 *
 * <p>
 * Excluded paths (same as JwtAuthFilter): GET /, GET /health,
 * GET /health/startup — pass through immediately without incrementing.
 */
@Requires(property = "jwt.secret")
@ServerFilter("/**")
public class RateLimitFilter implements Ordered {

    private static final Logger logger = Logger.getLogger(RateLimitFilter.class.getName());

    private final int requestsPerMinute;
    private final Cache<String, AtomicInteger> rateLimitCache;

    public RateLimitFilter(
            @Value("${complai.rate-limit.requests-per-minute:20}") int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
        this.rateLimitCache = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build();
    }

    @Override
    public int getOrder() {
        // Must be higher than JwtAuthFilter's default order (0) so this filter runs
        // after it.
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @RequestFilter
    @Nullable
    public MutableHttpResponse<?> filter(MutableHttpRequest<?> request) {
        if (isExcluded(request)) {
            return null;
        }

        String userId = request.getAttribute(JwtAuthFilter.USER_ATTRIBUTE, String.class).orElse("anonymous");
        AtomicInteger counter = rateLimitCache.get(userId, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();

        if (count > requestsPerMinute) {
            logger.warning(() -> "Rate limit exceeded — user=" + userId + " count=" + count);
            Map<String, Object> body = Map.of(
                    "success", false,
                    "message", "Rate limit exceeded. Try again later.",
                    "errorCode", OpenRouterErrorCode.RATE_LIMITED.getCode());
            return HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
        }

        return null;
    }

    private boolean isExcluded(HttpRequest<?> request) {
        String path = request.getPath();
        HttpMethod method = request.getMethod();
        return HttpMethod.GET.equals(method)
                && (path.equals("/") || path.equals("/health") || path.equals("/health/startup"));
    }
}
