package cat.complai.helpers.openrouter.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional deterministic token expansion for civic vocabulary variants.
 */
public final class DeterministicQueryExpansion {

    private static final Map<String, List<String>> PROCEDURE_ALIASES = Map.of(
            "municipal", List.of("procediment", "tramits"),
            "requirements", List.of("requisits"),
            "license", List.of("licencia", "llicencia"),
            "waste", List.of("residus", "trash"));

    private static final Map<String, List<String>> EVENT_ALIASES = Map.of(
            "kids", List.of("children", "family"),
            "culture", List.of("cultural"),
            "sports", List.of("sport"),
            "concert", List.of("music"),
            "cinema", List.of("film"));

    private DeterministicQueryExpansion() {
    }

    public static List<String> expandProcedureQueryTokens(List<String> baseTokens, boolean enabled) {
        return expand(baseTokens, PROCEDURE_ALIASES, enabled);
    }

    public static List<String> expandEventQueryTokens(List<String> baseTokens, boolean enabled) {
        return expand(baseTokens, EVENT_ALIASES, enabled);
    }

    private static List<String> expand(List<String> baseTokens, Map<String, List<String>> aliases, boolean enabled) {
        if (!enabled || baseTokens.isEmpty()) {
            return baseTokens;
        }

        Set<String> deduplicated = new LinkedHashSet<>(baseTokens);
        for (String token : baseTokens) {
            List<String> mapped = aliases.get(token);
            if (mapped != null) {
                deduplicated.addAll(mapped);
            }
        }

        return new ArrayList<>(deduplicated);
    }

    public static Map<String, Integer> termFrequency(List<String> tokens) {
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (String token : tokens) {
            int current = frequency.getOrDefault(token, 0);
            frequency.put(token, current + 1);
        }
        return frequency;
    }
}