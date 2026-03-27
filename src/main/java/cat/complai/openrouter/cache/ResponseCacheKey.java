package cat.complai.openrouter.cache;

import io.micronaut.core.annotation.Introspected;

/**
 * Immutable cache key for OpenRouter API response caching.
 * 
 * PRIVACY GUARANTEES:
 * - NO user query text (deterministic categorization only)
 * - NO conversation history
 * - Contains ONLY: cityId + procedure context hash + event context hash +
 * category
 * - Hash-based to prevent accidental PII leakage from long strings
 * 
 * Cache hits occur when:
 * - Same cityId
 * - Same procedure context (same RAG search results)
 * - Same event context (same RAG search results)
 * - Same question category (keyword-based detection)
 * 
 * Result: Different users querying similar questions in same city with same
 * context get cached response.
 * 
 * Java Record properties:
 * - Automatically generates immutable equals(), hashCode(), toString()
 * - Thread-safe field access
 * - Suitable as HashMap key
 */
@Introspected
public record ResponseCacheKey(
        String cityId,
        long procedureContextHash,
        long eventContextHash,
        QuestionCategory category,
        int questionHash) {
    /**
     * Compact constructor ensures validity.
     * All fields are required and must be non-null.
     */
    public ResponseCacheKey {
        if (cityId == null || cityId.isBlank())
            throw new IllegalArgumentException("cityId cannot be null or blank");
        if (category == null)
            throw new IllegalArgumentException("category cannot be null");
    }

    /**
     * Override toString for privacy-safe logging.
     * Includes cityId, category, and question hash, but NOT raw user query text.
     */
    @Override
    public String toString() {
        return String.format("ResponseCacheKey(city=%s, procHash=%d, eventHash=%d, category=%s, qHash=%d)",
                cityId, procedureContextHash, eventContextHash, category, questionHash);
    }
}
