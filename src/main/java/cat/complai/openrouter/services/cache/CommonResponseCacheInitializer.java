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
 * common
 * questions that are answered the same way regardless of who asks.
 * 
 * Loads common-ai-requests.json at application startup and populates the cache
 * with city-specific and global responses. This can improve cache hit rate by
 * 20-30%
 * for typical municipal question patterns.
 * 
 * PRIVACY NOTE:
 * Common responses are pre-configured answers (not user-generated),
 * and cache keys are deterministic (no user-specific data).
 * This is fully privacy-safe.
 * 
 * Missing file handling: If common-ai-requests.json is not found, logs a
 * warning
 * and continues. The cache still works; just with fewer pre-populated entries.
 */
@Singleton
public class CommonResponseCacheInitializer {

    private static final Logger LOGGER = Logger.getLogger(CommonResponseCacheInitializer.class.getName());
    private static final String CONFIG_FILE = "common-ai-requests.json";

    private final ResponseCacheService cacheService;
    private final ObjectMapper objectMapper;

    @Inject
    public CommonResponseCacheInitializer(ResponseCacheService cacheService, ObjectMapper objectMapper) {
        this.cacheService = cacheService;
        this.objectMapper = objectMapper;
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
     * Load common response entries from JSON file.
     * 
     * @return List of CommonResponseEntry records
     * @throws IOException if file not found or malformed
     */
    private List<CommonResponseEntry> loadCommonResponses() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is == null) {
                LOGGER.warning(() -> "common-ai-requests.json not found on classpath");
                return List.of();
            }

            CommonResponseEntry[] entries = objectMapper.readValue(is, CommonResponseEntry[].class);
            LOGGER.info(() -> "Loaded " + entries.length + " common responses from " + CONFIG_FILE);
            return Arrays.asList(entries);
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
