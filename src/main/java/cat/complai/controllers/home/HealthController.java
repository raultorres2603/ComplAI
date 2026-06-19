package cat.complai.controllers.home;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import jakarta.inject.Inject;

import cat.complai.dto.home.HealthDto;
import cat.complai.services.health.HealthService;

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
