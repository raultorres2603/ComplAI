package cat.complai.utilities.auth;

/**
 * Utility for city-level configuration checks.
 *
 * <p>Replaces the static method previously on {@code ApiKeyAuthFilter}.
 * Used by {@link JwtSessionAuthFilter}, {@code TokenController}, and
 * {@code TelegramController} to determine whether a city is enabled.
 */
public final class CityUtil {

    private CityUtil() {
        // Utility class — no instantiation
    }

    /**
     * Returns {@code true} if the given city is enabled via the
     * {@code ENABLE_CITY_<CITYID>} environment variable.
     * Defaults to disabled ({@code false}) when the variable is absent.
     *
     * <p>Checks system properties first (for test overrides), then environment
     * variables (production behaviour on Lambda).
     *
     * @param cityId the lowercase city identifier (e.g. {@code "elprat"})
     * @return {@code true} only when the value is {@code "true"}
     */
    public static boolean isCityEnabled(String cityId) {
        String key = "ENABLE_CITY_" + cityId.toUpperCase();
        // System property takes precedence (for test overrides)
        String enabled = System.getProperty(key);
        if (enabled == null) {
            enabled = System.getenv().getOrDefault(key, "false");
        }
        return "true".equalsIgnoreCase(enabled);
    }
}
