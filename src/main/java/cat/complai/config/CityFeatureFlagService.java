package cat.complai.config;

import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Service that reads per-city feature flags from environment variables.
 *
 * <p>
 * At startup it scans all environment variables for the prefix
 * {@code ENABLE_CITY_<CITYID>}. A city is considered enabled when the
 * corresponding env var is set to {@code "true"} (case-insensitive).
 * If the env var is absent, empty, or set to any other value, the city
 * is considered disabled.
 *
 * <p>
 * This follows the same convention as
 * {@link TelegramConfiguration} and
 * {@link cat.complai.utilities.auth.ApiKeyAuthFilter} which also scan
 * environment variables for per-city configuration.
 *
 * <p>
 * Usage example:
 * <pre>{@code
 * ENABLE_CITY_ELPRAT=true    → elprat is enabled
 * ENABLE_CITY_TESTCITY=false → testcity is disabled
 * (not set)                  → city is disabled (safe default)
 * }</pre>
 */
@Singleton
public class CityFeatureFlagService {

    private static final Logger logger = Logger.getLogger(CityFeatureFlagService.class.getName());
    private static final String ENV_PREFIX = "ENABLE_CITY_";

    private final Map<String, Boolean> cityEnabled;

    /**
     * Production constructor — scans {@link System#getenv()} at startup.
     * Cities without an {@code ENABLE_CITY_*} variable default to disabled.
     */
    public CityFeatureFlagService() {
        Map<String, Boolean> map = new HashMap<>();

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(ENV_PREFIX)) {
                String cityId = key.substring(ENV_PREFIX.length()).toLowerCase();
                boolean enabled = Boolean.parseBoolean(entry.getValue());
                map.put(cityId, enabled);
            }
        }

        // Log the configured flags. Note: we log the city IDs without their
        // boolean values to avoid misleading logs when values look "wrong"
        // (e.g. "true" is case-insensitive, but "1" is not true).
        this.cityEnabled = Map.copyOf(map);
        if (map.isEmpty()) {
            logger.info("CityFeatureFlagService initialized — no ENABLE_CITY_* environment variables found. "
                    + "All cities are disabled by default.");
        } else {
            long enabledCount = map.values().stream().filter(v -> v).count();
            logger.info("CityFeatureFlagService initialized with " + map.size()
                    + " city flag(s) (" + enabledCount + " enabled, "
                    + (map.size() - enabledCount) + " disabled): "
                    + String.join(", ", map.keySet()));
        }
    }

    /**
     * Test-only constructor — accepts a pre-built map instead of scanning
     * environment variables.
     *
     * @param cityEnabled map of cityId → enabled flag
     */
    public CityFeatureFlagService(Map<String, Boolean> cityEnabled) {
        this.cityEnabled = Map.copyOf(cityEnabled);
        long enabledCount = cityEnabled.values().stream().filter(v -> v).count();
        logger.fine(() -> "CityFeatureFlagService (test) initialized with " + cityEnabled.size()
                + " city flag(s) (" + enabledCount + " enabled).");
    }

    /**
     * Returns {@code true} if the given city is enabled via its
     * {@code ENABLE_CITY_<cityId>} environment variable.
     *
     * <p>
     * If the city has no env var, it is considered disabled (safe default).
     *
     * @param cityId the lowercased municipality identifier
     * @return {@code true} if the city is enabled
     */
    public boolean isCityEnabled(String cityId) {
        if (cityId == null) {
            return false;
        }
        return cityEnabled.getOrDefault(cityId, false);
    }
}
