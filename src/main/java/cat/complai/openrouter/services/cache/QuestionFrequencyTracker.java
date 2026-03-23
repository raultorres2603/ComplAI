package cat.complai.openrouter.services.cache;

import cat.complai.openrouter.cache.QuestionCategory;
import cat.complai.openrouter.cache.QuestionFrequencyEntry;
import jakarta.inject.Singleton;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory frequency tracking for questions.
 * 
 * Tracks high-frequency questions across users and cities to identify
 * candidates
 * for auto-promotion to the common responses cache.
 * 
 * Thread-safe: Uses ConcurrentHashMap at all levels for lock-free concurrent
 * access.
 * Data loss acceptable: Lambda is short-lived; cumulative hits are captured by
 * SQS events.
 * 
 * Privacy-safe: Stores only city, category, context hashes, and normalized
 * questions.
 * No raw user queries or PII.
 * 
 * Data structure:
 * ConcurrentHashMap<String city, ConcurrentHashMap<String key,
 * QuestionFrequencyEntry>>
 * └─ city: city identifier
 * └─ key: {category}_{procHash}_{eventHash}_{normalizedQuestion}
 * └─ entry: frequency, first/last seen timestamps, promotion flag
 */
@Singleton
public class QuestionFrequencyTracker {

    private static final Logger LOGGER = Logger.getLogger(QuestionFrequencyTracker.class.getName());

    // Per-city frequency maps: city ID -> (question key -> frequency entry)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, QuestionFrequencyEntry>> cityFrequencies;

    public QuestionFrequencyTracker() {
        this.cityFrequencies = new ConcurrentHashMap<>();
        LOGGER.info("QuestionFrequencyTracker initialized");
    }

    /**
     * Track a question occurrence.
     * 
     * If the question is new, this is the first occurrence (hitCount = 1).
     * If the question exists, increment hitCount and update lastSeen.
     * 
     * @param city                 City identifier
     * @param category             Auto-detected question category
     * @param procedureContextHash Hash of procedure context (0 if none)
     * @param eventContextHash     Hash of event context (0 if none)
     * @param normalizedQuestion   Normalized/hashed question string (privacy-safe)
     */
    public void trackQuestion(String city, QuestionCategory category, long procedureContextHash,
            long eventContextHash, String normalizedQuestion) {
        if (city == null || city.isBlank() || category == null || normalizedQuestion == null) {
            LOGGER.warning(() -> "trackQuestion called with null/blank parameters. Ignoring.");
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        // Create or get city's frequency map
        ConcurrentHashMap<String, QuestionFrequencyEntry> cityMap = cityFrequencies
                .computeIfAbsent(city, k -> new ConcurrentHashMap<>());

        // Build key for this specific question
        String key = String.format("%s_%d_%d_%s",
                category.name(),
                procedureContextHash,
                eventContextHash,
                normalizedQuestion);

        // Create or update frequency entry
        cityMap.compute(key, (k, existingEntry) -> {
            if (existingEntry == null) {
                // First occurrence of this question
                QuestionFrequencyEntry newEntry = new QuestionFrequencyEntry(
                        city,
                        category,
                        procedureContextHash,
                        eventContextHash,
                        normalizedQuestion,
                        1, // hitCount = 1 (first occurrence)
                        null, // promotionFlaggedAt
                        now, // firstSeen
                        now); // lastSeen
                LOGGER.fine(() -> String.format(
                        "FREQUENCY TRACK (NEW): city=%s, category=%s, key=%s, hits=1",
                        city, category.name(), key));
                return newEntry;
            } else {
                // Increment existing entry
                QuestionFrequencyEntry updated = existingEntry.incrementHit();
                LOGGER.fine(() -> String.format(
                        "FREQUENCY TRACK (UPDATE): city=%s, category=%s, key=%s, hits=%d",
                        city, category.name(), key, updated.hitCount()));
                return updated;
            }
        });
    }

    /**
     * Get a frequency summary for a specific city.
     * 
     * Returns a map of (question key -> hit count) for all tracked questions in the
     * city.
     * Returns an empty map if city has no tracked questions.
     * 
     * @param city City identifier
     * @return Immutable map of question key -> hit count (empty if city not found)
     */
    public Map<String, Long> getFrequencySummary(String city) {
        ConcurrentHashMap<String, QuestionFrequencyEntry> cityMap = cityFrequencies.get(city);
        if (cityMap == null || cityMap.isEmpty()) {
            return Collections.emptyMap();
        }

        // Create immutable copy with only hitCounts
        Map<String, Long> summary = new HashMap<>();
        cityMap.forEach((key, entry) -> summary.put(key, entry.hitCount()));
        return Collections.unmodifiableMap(summary);
    }

    /**
     * Get all frequency entries for a specific city.
     * Used by FrequencyPromotionScheduler to identify promotion candidates.
     * 
     * @param city City identifier
     * @return Immutable collection of QuestionFrequencyEntry (empty if city not
     *         found)
     */
    public Map<String, QuestionFrequencyEntry> getFrequencyEntries(String city) {
        ConcurrentHashMap<String, QuestionFrequencyEntry> cityMap = cityFrequencies.get(city);
        if (cityMap == null || cityMap.isEmpty()) {
            return Collections.emptyMap();
        }

        return Collections.unmodifiableMap(new HashMap<>(cityMap));
    }

    /**
     * Clear all frequency tracking for a specific city.
     * Useful for testing or resetting frequency state.
     * 
     * @param city City identifier
     */
    public void clearFrequencies(String city) {
        ConcurrentHashMap<String, QuestionFrequencyEntry> removed = cityFrequencies.remove(city);
        if (removed != null && !removed.isEmpty()) {
            LOGGER.info(() -> String.format("Cleared %d frequency entries for city=%s",
                    removed.size(), city));
        }
    }

    /**
     * Clear ALL frequency tracking across all cities.
     * Use with caution (typically after promotion/S3 update).
     */
    public void clearAllFrequencies() {
        int totalEntries = cityFrequencies.values().stream()
                .mapToInt(ConcurrentHashMap::size)
                .sum();
        cityFrequencies.clear();
        LOGGER.info(() -> String.format("Cleared all %d frequency entries across all cities",
                totalEntries));
    }

    /**
     * Get overall frequency statistics per city (for observability).
     * 
     * @return Map of city -> entry count
     */
    public Map<String, Integer> getStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        cityFrequencies.forEach((city, map) -> stats.put(city, map.size()));
        return Collections.unmodifiableMap(stats);
    }
}
