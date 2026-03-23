package cat.complai.openrouter.services.cache;

import cat.complai.openrouter.cache.ResponseCacheKey;
import cat.complai.openrouter.cache.QuestionCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResponseCacheService Tests")
class ResponseCacheServiceTest {

    private ResponseCacheService cacheService;

    @BeforeEach
    void setUp() {
        // Initialize cache with small limits for testing
        cacheService = new ResponseCacheService(true, 10, 100);
    }

    @Test
    @DisplayName("should store and retrieve cached response")
    void testStoreAndRetrieveCachedResponse() {
        ResponseCacheKey key = new ResponseCacheKey("testcity", 111, 222, QuestionCategory.PARKING);
        String response = "This is a cached parking response.";

        cacheService.cacheResponse(key, response);
        Optional<String> retrieved = cacheService.getCachedResponse(key);

        assertTrue(retrieved.isPresent());
        assertEquals(response, retrieved.get());
    }

    @Test
    @DisplayName("should return empty Optional on cache miss")
    void testReturnEmptyOnCacheMiss() {
        ResponseCacheKey key = new ResponseCacheKey("testcity", 111, 222, QuestionCategory.TAX);

        Optional<String> retrieved = cacheService.getCachedResponse(key);

        assertFalse(retrieved.isPresent());
    }

    @Test
    @DisplayName("should not cache null responses")
    void testDoNotCacheNullResponses() {
        ResponseCacheKey key = new ResponseCacheKey("testcity", 111, 222, QuestionCategory.LIBRARY);

        cacheService.cacheResponse(key, null);
        Optional<String> retrieved = cacheService.getCachedResponse(key);

        assertFalse(retrieved.isPresent());
    }

    @Test
    @DisplayName("should not cache blank responses")
    void testDoNotCacheBlankResponses() {
        ResponseCacheKey key = new ResponseCacheKey("testcity", 111, 222, QuestionCategory.GARBAGE);

        cacheService.cacheResponse(key, "   ");
        Optional<String> retrieved = cacheService.getCachedResponse(key);

        assertFalse(retrieved.isPresent());
    }

    @Test
    @DisplayName("should work with multiple independent entries")
    void testMultipleIndependentEntries() {
        ResponseCacheKey key1 = new ResponseCacheKey("city1", 111, 222, QuestionCategory.PARKING);
        ResponseCacheKey key2 = new ResponseCacheKey("city2", 111, 222, QuestionCategory.TAX);

        String response1 = "Parking response";
        String response2 = "Tax response";

        cacheService.cacheResponse(key1, response1);
        cacheService.cacheResponse(key2, response2);

        assertEquals(response1, cacheService.getCachedResponse(key1).get());
        assertEquals(response2, cacheService.getCachedResponse(key2).get());
    }

    @Test
    @DisplayName("should overwrite existing cache entries")
    void testOverwriteExistingEntries() {
        ResponseCacheKey key = new ResponseCacheKey("testcity", 111, 222, QuestionCategory.PARKING);

        cacheService.cacheResponse(key, "First response");
        cacheService.cacheResponse(key, "Second response");

        assertEquals("Second response", cacheService.getCachedResponse(key).get());
    }

    @Test
    @DisplayName("should invalidate all entries for a city")
    void testInvalidateCityEntries() {
        ResponseCacheKey key1 = new ResponseCacheKey("city1", 111, 222, QuestionCategory.PARKING);
        ResponseCacheKey key2 = new ResponseCacheKey("city1", 333, 444, QuestionCategory.TAX);
        ResponseCacheKey key3 = new ResponseCacheKey("city2", 111, 222, QuestionCategory.LIBRARY);

        cacheService.cacheResponse(key1, "Response 1");
        cacheService.cacheResponse(key2, "Response 2");
        cacheService.cacheResponse(key3, "Response 3");

        cacheService.invalidateCity("city1");

        assertFalse(cacheService.getCachedResponse(key1).isPresent());
        assertFalse(cacheService.getCachedResponse(key2).isPresent());
        assertTrue(cacheService.getCachedResponse(key3).isPresent(), "Other city should not be affected");
    }

    @Test
    @DisplayName("should invalidate all entries")
    void testInvalidateAll() {
        ResponseCacheKey key1 = new ResponseCacheKey("city1", 111, 222, QuestionCategory.PARKING);
        ResponseCacheKey key2 = new ResponseCacheKey("city2", 333, 444, QuestionCategory.TAX);

        cacheService.cacheResponse(key1, "Response 1");
        cacheService.cacheResponse(key2, "Response 2");

        cacheService.invalidateAll();

        assertFalse(cacheService.getCachedResponse(key1).isPresent());
        assertFalse(cacheService.getCachedResponse(key2).isPresent());
    }

    @Test
    @DisplayName("should report cache statistics")
    void testGetCacheStats() {
        ResponseCacheKey key = new ResponseCacheKey("testcity", 111, 222, QuestionCategory.PARKING);

        cacheService.cacheResponse(key, "Response");
        cacheService.getCachedResponse(key); // Cache hit
        cacheService.getCachedResponse(new ResponseCacheKey("testcity", 999, 999, QuestionCategory.OTHER)); // Cache
                                                                                                            // miss

        Object stats = cacheService.getStats();

        assertNotNull(stats, "Cache stats should be available when caching is enabled");
    }

    @Test
    @DisplayName("should report current cache size")
    void testGetCacheSize() {
        ResponseCacheKey key1 = new ResponseCacheKey("city1", 111, 222, QuestionCategory.PARKING);
        ResponseCacheKey key2 = new ResponseCacheKey("city2", 333, 444, QuestionCategory.TAX);

        cacheService.cacheResponse(key1, "Response 1");
        cacheService.cacheResponse(key2, "Response 2");

        assertEquals(2, cacheService.getSize());
    }

    @Test
    @DisplayName("should work with feature flag disabled")
    void testDisabledViaFeatureFlag() {
        ResponseCacheService disabledCache = new ResponseCacheService(false, 10, 100);
        ResponseCacheKey key = new ResponseCacheKey("testcity", 111, 222, QuestionCategory.PARKING);

        disabledCache.cacheResponse(key, "Response");
        Optional<String> retrieved = disabledCache.getCachedResponse(key);

        assertFalse(retrieved.isPresent(), "Cache should be disabled");
    }

    @Test
    @DisplayName("should handle concurrent access safely")
    void testConcurrentAccess() throws InterruptedException {
        ResponseCacheKey key = new ResponseCacheKey("testcity", 111, 222, QuestionCategory.PARKING);

        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                cacheService.cacheResponse(key, "Response " + i);
            }
        });

        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                cacheService.getCachedResponse(key);
            }
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // If we got here without exceptions, concurrent access is handled safely
        assertTrue(cacheService.getCachedResponse(key).isPresent());
    }

    @Test
    @DisplayName("should respect maximum cache size")
    void testRespectMaximumCacheSize() {
        // Create cache with max 5 entries
        ResponseCacheService smallCache = new ResponseCacheService(true, 10, 5);

        // Add 10 entries
        for (int i = 0; i < 10; i++) {
            ResponseCacheKey key = new ResponseCacheKey("city" + i, i, i, QuestionCategory.PARKING);
            smallCache.cacheResponse(key, "Response " + i);
        }

        // Cache size should not exceed 5
        long size = smallCache.getSize();
        assertTrue(size <= 5, "Cache size should not exceed maximum of 5");
    }

    @Test
    @DisplayName("should handle different context hashes independently")
    void testDifferentContextHashesIndependent() {
        ResponseCacheKey key1 = new ResponseCacheKey("city", 111, 222, QuestionCategory.PARKING);
        ResponseCacheKey key2 = new ResponseCacheKey("city", 111, 999, QuestionCategory.PARKING);

        cacheService.cacheResponse(key1, "Response 1");
        cacheService.cacheResponse(key2, "Response 2");

        assertEquals("Response 1", cacheService.getCachedResponse(key1).get());
        assertEquals("Response 2", cacheService.getCachedResponse(key2).get());
    }
}
