package cat.complai.home;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.HttpResponse;
import io.micronaut.core.annotation.Introspected;
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
}

@Singleton
class HealthService {
    public HealthDto getHealth() {
        boolean apiKeyConfigured = System.getenv("OPENROUTER_API_KEY") != null && !System.getenv("OPENROUTER_API_KEY").isBlank();
        // Optionally, add a shallow OpenRouter reachability check here
        return new HealthDto("UP", "1.0", Map.of(
                "openRouterApiKeyConfigured", apiKeyConfigured
        ));
    }
}

@Introspected
class HealthDto {
    private final String status;
    private final String version;
    private final Map<String, Object> checks;

    public HealthDto(String status, String version, Map<String, Object> checks) {
        this.status = status;
        this.version = version;
        this.checks = checks;
    }

    public String getStatus() { return status; }
    public String getVersion() { return version; }
    public Map<String, Object> getChecks() { return checks; }
}

