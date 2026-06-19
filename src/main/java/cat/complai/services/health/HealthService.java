package cat.complai.services.health;

import cat.complai.dto.home.HealthDto;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service that computes the health status of the application.
 *
 * <p>Exposes two health check modes:
 * <ul>
 *   <li>{@link #getHealth()} — full check including S3, SQS, SES, RAG, and
 *       OpenRouter dependencies</li>
 *   <li>{@link #getHealthStartup()} — lightweight check that does not trigger
 *       RAG initialization or AWS API calls, suitable for cold-start latency
 *       measurement</li>
 * </ul>
 */
@Singleton
public class HealthService {

    private final HealthCheckService healthCheckService;

    /**
     * Constructs the health service with the given health check service.
     *
     * @param healthCheckService service that performs individual dependency checks
     */
    @Inject
    public HealthService(HealthCheckService healthCheckService) {
        this.healthCheckService = healthCheckService;
    }

    /**
     * Returns the full health status, including checks for S3, SQS, SES, RAG
     * indexes, and the OpenRouter API key.
     *
     * @return a {@link HealthDto} with status "UP" and diagnostic checks
     */
    public HealthDto getHealth() {
        Map<String, Object> checks = new LinkedHashMap<>(healthCheckService.checkAll());
        return new HealthDto("UP", "1.0", checks);
    }

    /**
     * Lightweight startup health check that does NOT trigger RAG initialization
     * or any AWS API calls. Returns immediately with JVM status and the
     * OpenRouter API key presence as the only remote-dependency check, so it
     * can be used to measure Lambda cold-start time without I/O.
     *
     * @return a {@link HealthDto} with status "UP" and minimal checks
     */
    public HealthDto getHealthStartup() {
        String apiKey = System.getenv("OPENROUTER_API_KEY");
        boolean apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        return new HealthDto("UP", "1.0", Map.of(
                "jvm_alive", true,
                "openRouterApiKeyConfigured", apiKeyConfigured));
    }
}
