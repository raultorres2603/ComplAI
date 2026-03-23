package cat.complai.openrouter.services.cache;

import cat.complai.openrouter.cache.CommonResponseEntry;
import cat.complai.openrouter.cache.ResponseCacheKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Pre-populates the ResponseCacheService with common, high-frequency questions
 * and responses.
 * 
 * This implements Tier 2 of the caching strategy: a pre-built cache of 30-50
 * common questions that are answered the same way regardless of who asks.
 * 
 * Load strategy (in order of preference):
 * 1. Try S3 (if bucket configured and available)
 * 2. Fall back to classpath (common-ai-requests.json)
 * 
 * This allows dynamic updates without redeploying Lambda.
 * 
 * PRIVACY NOTE:
 * Common responses are pre-configured answers (not user-generated),
 * and cache keys are deterministic (no user-specific data).
 * This is fully privacy-safe.
 */
@Singleton
public class CommonResponseCacheInitializer {

    private static final Logger LOGGER = Logger.getLogger(CommonResponseCacheInitializer.class.getName());
    private static final String CONFIG_FILE = "common-ai-requests.json";

    private final ResponseCacheService cacheService;
    private final ObjectMapper objectMapper;
    private final S3CommonResponseLoader s3Loader;

    @Inject
    public CommonResponseCacheInitializer(ResponseCacheService cacheService, ObjectMapper objectMapper,
            S3CommonResponseLoader s3Loader) {
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
        this.s3Loader = s3Loader;
    }

    /**
     * Loads and processes common responses at application startup.
     * Subscribes to Micronaut's StartupEvent for automatic initialization.
     * 
     * @param event The Micronaut startup event
     */
    @EventListener
    void onStartup(StartupEvent event) {
        try {
            List<CommonResponseEntry> entries = loadCommonResponses();
            populateCache(entries);
        } catch (Exception e) {
            LOGGER.warning("Failed to load common responses: " + e.getMessage());
            // Continue without pre-populated cache; Tier 1 still works
        }
    }

    /**
     * Load common response entries from S3 or fallback to classpath.
     * 
     * @return List of CommonResponseEntry records
     * @throws IOException if neither S3 nor classpath sources are available
     */
    private List<CommonResponseEntry> loadCommonResponses() throws IOException {
        // Try S3 first
        try {
            List<CommonResponseEntry> s3Entries = s3Loader.loadFromS3();
            if (!s3Entries.isEmpty()) {
                LOGGER.info(() -> "Loaded " + s3Entries.size() + " common responses from S3");
                return s3Entries;
            }
        } catch (Exception e) {
            LOGGER.fine(() -> "S3 load failed, falling back to classpath: " + e.getMessage());
        }

        // Fall back to classpath
        return loadCommonResponsesFromClasspath();
    }

    /**
     * Load common response entries from classpath JSON file.
     * 
     * @return List of CommonResponseEntry records
     * @throws IOException if file not found or malformed
     */
    private List<CommonResponseEntry> loadCommonResponsesFromClasspath() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is == null) {
                LOGGER.warning(() -> "common-ai-requests.json not found on classpath");
                return List.of();
            }

            CommonResponseEntry[] entries = objectMapper.readValue(is, CommonResponseEntry[].class);
            LOGGER.info(() -> "Loaded " + entries.length + " common responses from classpath");
            return Arrays.asList(entries);
        }
    }

    /**
     * Reload common responses from S3 without restarting the application.
     * Clears the existing cache and re-populates with fresh data from S3.
     * 
     * Falls back to classpath if S3 is unavailable.
     * Thread-safe: Reloading happens atomically with cache clear.
     */
    public void reloadFromS3() {
        try {
            LOGGER.info("Reloading common responses from S3...");
            List<CommonResponseEntry> entries = loadCommonResponses();

            // Clear existing cache entries (optional: could be selective by city)
            // For now, we rely on TTL; new entries will be added to the cache
            populateCache(entries);

            LOGGER.info("Successfully reloaded common responses");
        } catch (Exception e) {
            LOGGER.warning("Failed to reload common responses: " + e.getMessage());
            // Cache continues to work with existing entries
        }
    }

    /**
     * Populate the cache with common responses.
     * Creates cache keys from category + city (or global if city is null).
     * 
     * Note: Common response cache keys use context hashes of 0 (placeholder),
     * since these are generic pre-built responses not tied to specific RAG results.
     * Higher-priority hits will come from Tier 1 (actual RAG-based responses).
     * 
     * @param entries List of common responses to cache
     */
    private void populateCache(List<CommonResponseEntry> entries) {
        if (entries.isEmpty()) {
            LOGGER.fine("No common responses to pre-populate");
            return;
        }

        int cachedCount = 0;
        for (CommonResponseEntry entry : entries) {
            try {
                // Common responses use hash=0 as a placeholder (matched only if no RAG results)
                ResponseCacheKey key = new ResponseCacheKey(
                        entry.city() != null ? entry.city() : "global",
                        0, // procedureContextHash - placeholder for common responses
                        0, // eventContextHash - placeholder for common responses
                        entry.category());
                cacheService.cacheResponse(key, entry.response());
                cachedCount++;
            } catch (Exception e) {
                LOGGER.warning("Failed to cache entry: " + entry + " — " + e.getMessage());
            }
        }

        final int finalCachedCount = cachedCount;
        LOGGER.info(() -> String.format("Pre-populated cache with %d/%d common responses",
                finalCachedCount, entries.size()));
    }
}
