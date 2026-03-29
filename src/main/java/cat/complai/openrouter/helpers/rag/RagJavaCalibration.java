package cat.complai.openrouter.helpers.rag;

import java.util.Locale;

/**
 * Centralized deterministic calibration knobs for Java in-memory retrieval.
 *
 * <p>
 * Values can be overridden at runtime with system properties (for harness
 * calibration loops) while keeping safe code defaults for production.
 */
public final class RagJavaCalibration {

    private static final String ROOT_KEY = "rag.retrieval.java";

    private RagJavaCalibration() {
    }

    public static DomainSettings procedure() {
        return new DomainSettings(
                doubleProperty("procedure.absolute-floor", 0.15d),
                doubleProperty("procedure.relative-floor", 0.45d),
                doubleProperty("procedure.title-boost", 2.0d),
                doubleProperty("procedure.description-boost", 1.0d),
                booleanProperty("procedure.expansion.enabled", false));
    }

    public static DomainSettings event() {
        return new DomainSettings(
                doubleProperty("event.absolute-floor", 0.15d),
                doubleProperty("event.relative-floor", 0.45d),
                doubleProperty("event.title-boost", 2.0d),
                doubleProperty("event.description-boost", 1.0d),
                booleanProperty("event.expansion.enabled", false));
    }

    public static ScorerSettings scorer() {
        return new ScorerSettings(
                doubleProperty("scorer.k1", 1.2d),
                doubleProperty("scorer.b", 0.75d),
                doubleProperty("scorer.idf-smoothing", 0.5d));
    }

    private static double doubleProperty(String suffix, double defaultValue) {
        String key = ROOT_KEY + "." + suffix;
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(toEnvKey(key));
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean booleanProperty(String suffix, boolean defaultValue) {
        String key = ROOT_KEY + "." + suffix;
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(toEnvKey(key));
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return "true".equals(raw.toLowerCase(Locale.ROOT).trim());
    }

    private static String toEnvKey(String propertyKey) {
        return propertyKey.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    public record DomainSettings(
            double absoluteFloor,
            double relativeFloor,
            double titleBoost,
            double descriptionBoost,
            boolean expansionEnabled) {
    }

    public record ScorerSettings(double k1, double b, double idfSmoothing) {
    }
}