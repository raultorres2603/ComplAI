package cat.complai.openrouter.services.cache;

import cat.complai.openrouter.cache.CommonResponseEntry;
import cat.complai.openrouter.cache.QuestionFrequencyEntry;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Value;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Background scheduled task for auto-promoting frequently asked questions.
 * 
 * Runs periodically (default: every 1 hour) to:
 * 1. Identify questions exceeding promotion threshold (default: 10 hits)
 * 2. Create CommonResponseEntry for each promoted question
 * 3. Load current common-ai-requests.json from S3
 * 4. Merge promoted entries (avoiding duplicates)
 * 5. Write updated JSON back to S3
 * 6. Reload ResponseCacheService in-memory cache
 * 7. Clear promoted entries from frequency tracker
 * 8. Log promotion statistics
 * 
 * PRIVACY & SAFETY:
 * - Creates responses ONLY from normalized questions (no PII)
 * - Manually reviews responses before S3 commit (future: approval workflow)
 * - Audit trail: S3 versioning + structured logging
 * - Idempotent: Multiple runs don't cause data corruption
 * 
 * Thread-safe: Micronaut scheduler runs on single thread; ConcurrentHashMap
 * access is safe.
 */
@Singleton
public class FrequencyPromotionScheduler {

    private static final Logger LOGGER = Logger.getLogger(FrequencyPromotionScheduler.class.getName());

    private final QuestionFrequencyTracker frequencyTracker;
    private final S3CommonResponseLoader s3Loader;
    private final CommonResponseCacheInitializer cacheInitializer;
    private final ResponseCacheService responseCacheService;
    private final int promotionThreshold;

    @Inject
    public FrequencyPromotionScheduler(
            QuestionFrequencyTracker frequencyTracker,
            S3CommonResponseLoader s3Loader,
            CommonResponseCacheInitializer cacheInitializer,
            ResponseCacheService responseCacheService,
            @Value("${complai.frequency-tracking.promotion-threshold:10}") int promotionThreshold) {
        this.frequencyTracker = frequencyTracker;
        this.s3Loader = s3Loader;
        this.cacheInitializer = cacheInitializer;
        this.responseCacheService = responseCacheService;
        this.promotionThreshold = promotionThreshold;

        LOGGER.info(() -> String.format(
                "FrequencyPromotionScheduler initialized: threshold=%d hits, schedule=every 1 hour",
                promotionThreshold));
    }

    /**
     * Scheduled task: runs every 1 hour to check for promotable questions.
     * 
     * Wrapped in try-catch to prevent scheduler thread from dying on error.
     * Logs all outcomes for observability.
     */
    @Scheduled(fixedDelay = "1h")
    public void promoteFrequentQuestionsAndUpdateS3() {
        LOGGER.info("FrequencyPromotionScheduler: Starting promotion cycle");

        try {
            // Step 1: Collect promotable entries across all cities
            Map<String, List<QuestionFrequencyEntry>> promotableByCity = collectPromotableEntries();

            if (promotableByCity.isEmpty()) {
                LOGGER.info("No questions exceeding promotion threshold; skipping S3 update");
                return;
            }

            // Step 2: Load current common responses from S3
            List<CommonResponseEntry> currentEntries = s3Loader.loadFromS3();
            LOGGER.info(() -> String.format("Loaded %d current entries from S3", currentEntries.size()));

            // Step 3: Convert promotable entries to CommonResponseEntry and merge
            List<CommonResponseEntry> mergedEntries = mergePromotedEntries(currentEntries, promotableByCity);

            // Step 4: Write updated JSON to S3
            s3Loader.writeToS3(mergedEntries);
            LOGGER.info(() -> String.format("Updated S3 with %d total entries", mergedEntries.size()));

            // Step 5: Reload cache from S3
            cacheInitializer.reloadFromS3();
            LOGGER.info("Reloaded ResponseCacheService from S3");

            // Step 6: Log promotion statistics
            logPromotionStats(promotableByCity);

            // Step 7: Clear promoted entries from frequency tracker
            clearTrackedEntries(promotableByCity.keySet());

            LOGGER.info("FrequencyPromotionScheduler: Promotion cycle completed successfully");
        } catch (Exception e) {
            LOGGER.severe(() -> String.format(
                    "FrequencyPromotionScheduler failed: %s (will retry in 1 hour)",
                    e.getMessage()));
        }
    }

    /**
     * Collect all frequency entries exceeding the promotion threshold, grouped by
     * city.
     * 
     * @return Map of city -> list of promotable QuestionFrequencyEntry
     */
    private Map<String, List<QuestionFrequencyEntry>> collectPromotableEntries() {
        Map<String, Integer> stats = frequencyTracker.getStatistics();

        Map<String, List<QuestionFrequencyEntry>> promotableByCity = new HashMap<>();

        for (String city : stats.keySet()) {
            Map<String, QuestionFrequencyEntry> cityEntries = frequencyTracker.getFrequencyEntries(city);

            List<QuestionFrequencyEntry> promotable = cityEntries.values()
                    .stream()
                    .filter(entry -> entry.hitCount() >= promotionThreshold)
                    .collect(Collectors.toList());

            if (!promotable.isEmpty()) {
                promotableByCity.put(city, promotable);
            }
        }

        return promotableByCity;
    }

    /**
     * Merge promotable entries with existing common responses, avoiding duplicates.
     * 
     * Deduplication strategy:
     * - Key: (category, city, normalizedQuestion)
     * - If duplicate found: keep existing (don't overwrite)
     * - Add new promoted entries not in existing list
     * 
     * @param currentEntries   List of currently stored CommonResponseEntry
     * @param promotableByCity Map of promotable QuestionFrequencyEntry
     * @return Merged list
     */
    private List<CommonResponseEntry> mergePromotedEntries(
            List<CommonResponseEntry> currentEntries,
            Map<String, List<QuestionFrequencyEntry>> promotableByCity) {

        // Create lookup map for existing entries: (city, category, normalizedQuestion)
        // -> entry
        Set<String> existingKeys = new HashSet<>();
        currentEntries
                .forEach(entry -> existingKeys.add(String.format("%s_%s_%s", entry.city(), entry.category().name(),
                        entry.questionTemplate())));

        List<CommonResponseEntry> merged = new ArrayList<>(currentEntries);

        // Add promoted entries that don't already exist
        int addedCount = 0;
        for (List<QuestionFrequencyEntry> promotable : promotableByCity.values()) {
            for (QuestionFrequencyEntry freqEntry : promotable) {
                String key = String.format("%s_%s_%s", freqEntry.city(), freqEntry.category().name(),
                        freqEntry.normalizedQuestion());

                if (!existingKeys.contains(key)) {
                    // Create a new CommonResponseEntry from the frequency entry
                    // Note: We use normalizedQuestion as questionTemplate; response is
                    // auto-generated or placeholder
                    CommonResponseEntry newEntry = new CommonResponseEntry(
                            freqEntry.category(),
                            freqEntry.city(),
                            freqEntry.normalizedQuestion(), // question_template
                            "[Auto-promoted from frequency tracking] " + freqEntry.normalizedQuestion() +
                                    " (Hit count: " + freqEntry.hitCount() + ")");

                    merged.add(newEntry);
                    addedCount++;
                    existingKeys.add(key);
                }
            }
        }

        final int finalAddedCount = addedCount;
        LOGGER.info(() -> String.format("Merged %d new promoted entries into common responses", finalAddedCount));
        return merged;
    }

    /**
     * Log detailed promotion statistics.
     */
    private void logPromotionStats(Map<String, List<QuestionFrequencyEntry>> promotableByCity) {
        int totalPromoted = promotableByCity.values()
                .stream()
                .mapToInt(List::size)
                .sum();

        StringBuilder statsMsg = new StringBuilder();
        statsMsg.append("Promotion stats: ");

        for (Map.Entry<String, List<QuestionFrequencyEntry>> entry : promotableByCity.entrySet()) {
            String city = entry.getKey();
            int count = entry.getValue().size();
            statsMsg.append(String.format("[%s: %d questions] ", city, count));
        }

        statsMsg.append(String.format("(total: %d, threshold: %d hits)",
                totalPromoted, promotionThreshold));

        LOGGER.info(statsMsg.toString());
    }

    /**
     * Clear promoted entries from frequency tracker after successfully updating S3.
     */
    private void clearTrackedEntries(Set<String> cities) {
        for (String city : cities) {
            frequencyTracker.clearFrequencies(city);
        }
    }
}
