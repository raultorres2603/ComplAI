package cat.complai.home;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;

/**
 * HTTP controller exposing Lambda liveness and startup health checks.
 *
 * <p>Two endpoints are provided:
 * <ul>
 *   <li>{@code GET /health} — full liveness check, reports whether the OpenRouter API key
 *       is configured.</li>
 *   <li>{@code GET /health/startup} — lightweight probe that returns immediately without
 *       performing any I/O. Suitable for Lambda warm-up checks and monitoring scripts
 *       that must not trigger RAG index initialisation.</li>
 * </ul>
 *
 * <p>Both endpoints are excluded from API key authentication and can be called anonymously.
 */
@Controller("/health")
public class HealthController {
    private final HealthService healthService;

    @Inject
    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * Returns the full liveness status of the application.
     *
     * @return {@code 200 OK} with {@link HealthDto} containing status, version, and health checks
     */
    @Get
    public HttpResponse<HealthDto> health() {
        HealthDto dto = healthService.getHealth();
        return HttpResponse.ok(dto);
    }

    /**
     * Returns a lightweight startup health check that does not trigger RAG initialisation.
     *
     * @return {@code 200 OK} with {@link HealthDto} confirming JVM liveness and API key presence
     */
    @Get("/startup")
    public HttpResponse<HealthDto> healthStartup() {
        HealthDto dto = healthService.getHealthStartup();
        return HttpResponse.ok(dto);
    }
}

@Singleton
class HealthService {
    public HealthDto getHealth() {
        boolean apiKeyConfigured = System.getenv("OPENROUTER_API_KEY") != null
                && !System.getenv("OPENROUTER_API_KEY").isBlank();
        // Optionally, add a shallow OpenRouter reachability check here
        return new HealthDto("UP", "1.0", Map.of(
                "openRouterApiKeyConfigured", apiKeyConfigured));
    }

    /**
     * Lightweight startup health check that does NOT trigger RAG initialization.
     * Returns immediately without I/O to measure Lambda cold-start time.
     */
    public HealthDto getHealthStartup() {
        boolean apiKeyConfigured = System.getenv("OPENROUTER_API_KEY") != null
                && !System.getenv("OPENROUTER_API_KEY").isBlank();
        return new HealthDto("UP", "1.0", Map.of(
                "jvm_alive", true,
                "openRouterApiKeyConfigured", apiKeyConfigured));
    }
}
