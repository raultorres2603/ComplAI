package cat.complai.home;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.HttpResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Map;

@Controller("/health")
public class HealthController {
    private final HealthService healthService;

    @Inject
    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @Get
    public HttpResponse<HealthDto> health() {
        HealthDto dto = healthService.getHealth();
        return HttpResponse.ok(dto);
    }

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
