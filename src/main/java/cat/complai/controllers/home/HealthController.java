package cat.complai.controllers.home;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;

import cat.complai.dto.home.HealthDto;
import cat.complai.services.health.HealthCheckService;

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
     * Returns the full health status, including checks for S3, SQS, SES, RAG,
     * and OpenRouter configuration.
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
     * initialization or AWS API calls.
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

    private final HealthCheckService healthCheckService;

    /**
     * Constructs the health service with the given health check service.
     *
     * @param healthCheckService service that performs individual dependency checks
     */
    @Inject
    HealthService(HealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }

    /**
     * Returns the full health status, including checks for S3, SQS, SES, RAG
     * indexes, and the OpenRouter API key.
     *
     * @return a {@link HealthDto} with status "UP" and diagnostic checks
     */
    public HealthDto getHealth() {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.putAll(healthCheckService.checkAll());
        return new HealthDto("UP", "1.0", checks);
    }

    /**
     * Lightweight startup health check that does NOT trigger RAG initialization
     * or any AWS API calls. Returns immediately with JVM status and the
     * OpenRouter API key presence as the only remote-dependency check, so it
     * can be used to measure Lambda cold-start time without I/O.
     */
    public HealthDto getHealthStartup() {
        boolean apiKeyConfigured = System.getenv("OPENROUTER_API_KEY") != null
                && !System.getenv("OPENROUTER_API_KEY").isBlank();
        return new HealthDto("UP", "1.0", Map.of(
                "jvm_alive", true,
                "openRouterApiKeyConfigured", apiKeyConfigured));
    }
}
