package cat.complai.controllers.home;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;

import cat.complai.dto.home.HealthDto;

/**
 * Controller exposing health-check endpoints for monitoring and Lambda startup
 * validation.
 */
@Controller("/health")
public class HealthController {
    private final HealthService healthService;

    /**
     * Constructs the controller with the given health service.
     *
     * @param healthService service that computes health status
     */
    @Inject
    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * Returns the full health status, including an OpenRouter API key check.
     *
     * @return 200 OK with a {@link HealthDto}
     */
    @Get
    public HttpResponse<HealthDto> health() {
        HealthDto dto = healthService.getHealth();
        return HttpResponse.ok(dto);
    }

    /**
     * Returns a lightweight startup health check that does not trigger RAG
     * initialization.
     *
     * @return 200 OK with a {@link HealthDto}
     */
    @Get("/startup")
    public HttpResponse<HealthDto> healthStartup() {
        HealthDto dto = healthService.getHealthStartup();
        return HttpResponse.ok(dto);
    }
}

/**
 * Service that computes the health status of the application.
 */
@Singleton
class HealthService {
    /**
     * Returns the full health status, including a check for the OpenRouter API key
     * configuration.
     *
     * @return a {@link HealthDto} with status "UP" and diagnostic checks
     */
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
