package cat.complai.helpers.openrouter.rag;

import java.util.List;
import java.util.Locale;

public final class AmbiguityDetector {

    private static final String THRESHOLD_KEY = "rag.ambiguity.score-ratio-threshold";
    private static final double DEFAULT_THRESHOLD = 0.85d;

    private AmbiguityDetector() {
    }

    private static double readThreshold() {
        String raw = System.getProperty(THRESHOLD_KEY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(THRESHOLD_KEY.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        }
        if (raw == null || raw.isBlank()) {
            return DEFAULT_THRESHOLD;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return DEFAULT_THRESHOLD;
        }
    }

    public static <T> boolean isAmbiguous(InMemoryLexicalIndex.SearchResponse<T> response, double absoluteFloor) {
        List<SearchResult<T>> results = response.results();
        if (results.size() < 2) {
            return false;
        }
        double best = results.get(0).score();
        double second = results.get(1).score();
        if (best < absoluteFloor || second < absoluteFloor) {
            return false;
        }
        return second / best >= readThreshold();
    }

    public static <T> List<SearchResult<T>> getTopCandidates(InMemoryLexicalIndex.SearchResponse<T> response, int n) {
        List<SearchResult<T>> results = response.results();
        return results.subList(0, Math.min(n, results.size()));
    }
}
