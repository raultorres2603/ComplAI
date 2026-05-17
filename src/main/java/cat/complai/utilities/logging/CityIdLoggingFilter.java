package cat.complai.utilities.logging;

import org.slf4j.MDC;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import cat.complai.utilities.auth.ApiKeyAuthFilter;

/**
 * Filter that extracts cityId from request attributes and injects it into MDC
 * (Mapped Diagnostic Context) for all log entries.
 *
 * <p>This filter runs after ApiKeyAuthFilter which sets the "city" attribute.
 * It reads that attribute and makes it available in all logs via SLF4J MDC.
 *
 * <p>MDC is cleared on each new request.
 * Only active when api.key.enabled=true (matching ApiKeyAuthFilter behavior).
 */
@Requires(property = "api.key.enabled")
@ServerFilter("/**")
public class CityIdLoggingFilter {

    private static final String CITY_MDC_KEY = "cityId";

    @RequestFilter
    @Nullable
    public MutableHttpResponse<?> filter(MutableHttpRequest<?> request) {
        // Clear any previous MDC value first
        MDC.remove(CITY_MDC_KEY);

        // Extract cityId from request attributes (set by ApiKeyAuthFilter)
        request.getAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, String.class)
                .filter(id -> !id.isBlank())
                .ifPresent(id -> MDC.put(CITY_MDC_KEY, id));
        // If no cityId (e.g., excluded paths like /health), leave MDC empty

        // Return null to continue filter chain
        return null;
    }
}