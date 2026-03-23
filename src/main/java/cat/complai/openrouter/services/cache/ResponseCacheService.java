package cat.complai.openrouter.services.cache;

import cat.complai.openrouter.cache.ResponseCacheKey;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Tier 1 in-memory response cache using Caffeine.
 * 
 * This service provides fast, thread-safe caching of OpenRouter AI responses
 * based on hash keys (no user query text, no conversation history).
 * 
 * PRIVACY GUARANTEES:
 * - Cache key contains ONLY: cityId + procedure context hash + event context
 * hash + category
 * - Cache value contains ONLY: AI response text (success case)
 * - NO user queries, conversation history, or PII in cache
 * 
 * TTL and size are configurable:
 * - Default TTL: 10 minutes (balance between freshness and API reduction)
 * - Default max entries: 500 (lightweight for Lambda warm instances)
 * - Eviction: LRU (least recently used)
 * 
 * Thread safety: Caffeine handles all synchronization internally.
 * No manual locking needed for concurrent access.
 * 
 * Feature flag: Can be disabled via response.cache.enabled property (default:
 * true).
 */
@Singleton
public class ResponseCacheService {

    private static final Logger LOGGER = Logger.getLogger(ResponseCacheService.class.getName());

    private final Cache<ResponseCacheKey, String> cache;
    private final boolean cacheEnabled;

    public ResponseCacheService(
            @Value("${response.cache.enabled:true}") boolean cacheEnabled,
            @Value("${response.cache.ttl-minutes:10}") int ttlMinutes,
            @Value("${response.cache.max-entries:500}") int maxEntries) {
        this.cacheEnabled = cacheEnabled;

        if (cacheEnabled) {
            this.cache = Caffeine.newBuilder()
                    .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                    .maximumSize(maxEntries)
                    .recordStats() // Enable statistics for observability
                    .build();
            LOGGER.info(() -> String.format("ResponseCacheService initialized: ttl=%d min, maxEntries=%d",
                    ttlMinutes, maxEntries));
        } else {
            this.cache = null;
            LOGGER.info("ResponseCacheService disabled via response.cache.enabled=false");
        }
    }

    /**
     * Attempt to retrieve a cached response.
     * 
     * @param key The cache key (city + context hashes + category)
     * @return Optional containing cached response if present, empty() otherwise
     */
    public Optional<String> getCachedResponse(ResponseCacheKey key) {
        if (!cacheEnabled || cache == null) {
            return Optional.empty();
        }

        String cached = cache.getIfPresent(key);
        if (cached != null) {
            LOGGER.fine(() -> "CACHE HIT: " + key);
            return Optional.of(cached);
        }

        LOGGER.fine(() -> "CACHE MISS: " + key);
        return Optional.empty();
    }

    /**
     * Cache an AI response for future reuse.
     * 
     * @param key        The cache key (city + context hashes + category)
     * @param aiResponse The AI response text to cache
     */
    public void cacheResponse(ResponseCacheKey key, String aiResponse) {
        if (!cacheEnabled || cache == null) {
            return;
        }

        if (aiResponse == null || aiResponse.isBlank()) {
            LOGGER.fine(() -> "Not caching empty response for key: " + key);
            return;
        }

        cache.put(key, aiResponse);
        LOGGER.fine(() -> "CACHE STORED: " + key + " (size=" + aiResponse.length() + " chars)");
    }

    /**
     * Invalidate all cached responses for a specific city.
     * Useful when city-level data (procedures, events) is updated.
     * 
     * @param cityId The city to invalidate
     */
    public void invalidateCity(String cityId) {
        if (!cacheEnabled || cache == null) {
            return;
        }

        // Count entries before invalidation
        long beforeSize = cache.asMap().size();

        // Filter and remove all entries matching the city
        cache.asMap().keySet().removeIf(key -> key.cityId().equals(cityId));

        long afterSize = cache.asMap().size();
        long removedCount = beforeSize - afterSize;
        LOGGER.info(() -> String.format("Invalidated %d cache entries for city=%s", removedCount, cityId));
    }

    /**
     * Invalidate the entire cache.
     * Useful for emergency cache clearing or feature flag disabling.
     */
    public void invalidateAll() {
        if (!cacheEnabled || cache == null) {
            return;
        }

        long beforeSize = cache.asMap().size();
        cache.invalidateAll();
        LOGGER.info(() -> String.format("Invalidated all %d cache entries", beforeSize));
    }

    /**
     * Get cache statistics for observability and performance monitoring.
     * Returns null if caching is disabled.
     * 
     * Statistics include:
     * - hitCount: Total cache hits
     * - missCount: Total cache misses
     * - loadSuccessCount: Entries successfully cached
     * - evictionCount: Entries evicted due to size/TTL
     * 
     * @return Cache stats object, or null if disabled
     */
    public Object getStats() {
        if (!cacheEnabled || cache == null) {
            return null;
        }
        return cache.stats();
    }

    /**
     * Get current cache size.
     * 
     * @return Number of entries currently in cache
     */
    public long getSize() {
        if (!cacheEnabled || cache == null) {
            return 0;
        }
        return cache.asMap().size();
    }
}
