package cat.complai.home;

import io.micronaut.core.annotation.Introspected;
import java.util.Map;

@Introspected
public class HealthDto {
    private final String status;
    private final String version;
    private final Map<String, Object> checks;

    public HealthDto(String status, String version, Map<String, Object> checks) {
        this.status = status;
        this.version = version;
        this.checks = checks;
    }

    public String getStatus() {
        return status;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, Object> getChecks() {
        return checks;
    }
}
