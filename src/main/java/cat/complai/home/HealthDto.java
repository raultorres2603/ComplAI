package cat.complai.home;

import io.micronaut.core.annotation.Introspected;
import java.util.Map;

/**
 * DTO representing the health status of the ComplAI service.
 */
@Introspected
public class HealthDto {
    private final String status;
    private final String version;
    private final Map<String, Object> checks;

    /**
     * Constructs a {@code HealthDto} with a status string, version, and a map of
     * check results.
     *
     * @param status  overall health status (e.g. "UP")
     * @param version application version string
     * @param checks  map of named diagnostic checks and their values
     */
    public HealthDto(String status, String version, Map<String, Object> checks) {
        this.status = status;
        this.version = version;
        this.checks = checks;
    }

    /**
     * Returns the overall health status string.
     *
     * @return health status (e.g. "UP")
     */
    public String getStatus() {
        return status;
    }

    /**
     * Returns the application version string.
     *
     * @return version string
     */
    public String getVersion() {
        return version;
    }

    /**
     * Returns the map of named diagnostic check results.
     *
     * @return diagnostic checks keyed by check name
     */
    public Map<String, Object> getChecks() {
        return checks;
    }
}
