package cat.complai.openrouter.cache;

import java.time.LocalDateTime;

/**
 * In-memory representation of a tracked question for frequency analysis.
 * 
 * This record tracks question patterns for auto-promotion to common responses.
 * 
 * PRIVACY GUARANTEES:
 * - No raw user query text (only normalized/hashed)
 * - Only stores: city, category, context hashes, and normalized question hash
 * - No conversation history or PII
 * 
 * Fields:
 * - city: City identifier for scope isolation
 * - category: Auto-detected question category
 * - procedureContextHash: Hash of procedure context (0 if none)
 * - eventContextHash: Hash of event context (0 if none)
 * - normalizedQuestion: Normalized/hashed question string (privacy-safe)
 * - hitCount: Number of times this question has been asked
 * - promotionFlaggedAt: Timestamp when question was eligible for promotion (null if not yet)
 * - firstSeen: Timestamp of first occurrence
 * - lastSeen: Timestamp of most recent occurrence
 */
public record QuestionFrequencyEntry(
        String city,
        QuestionCategory category,
        long procedureContextHash,
        long eventContextHash,
        String normalizedQuestion,
        long hitCount,
        LocalDateTime promotionFlaggedAt,
        LocalDateTime firstSeen,
        LocalDateTime lastSeen) {

    /**
     * Compact constructor with validations.
     */
    public QuestionFrequencyEntry {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("city must not be null or blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            throw new IllegalArgumentException("normalizedQuestion must not be null or blank");
        }
        if (hitCount < 0) {
            throw new IllegalArgumentException("hitCount cannot be negative");
        }
        if (firstSeen != null && lastSeen != null && lastSeen.isBefore(firstSeen)) {
            throw new IllegalArgumentException("lastSeen cannot be before firstSeen");
        }
    }

    /**
     * Create a copy of this entry with incremented hit count and updated lastSeen.
     * 
     * @return A new QuestionFrequencyEntry with hitCount + 1 and lastSeen = now
     */
    public QuestionFrequencyEntry incrementHit() {
        LocalDateTime now = LocalDateTime.now();
        return new QuestionFrequencyEntry(
                city,
                category,
                procedureContextHash,
                eventContextHash,
                normalizedQuestion,
                hitCount + 1,
                promotionFlaggedAt,
                firstSeen,
                now); // Update lastSeen to now
    }

    /**
     * Create a copy of this entry marked for promotion.
     * 
     * @return A new QuestionFrequencyEntry with promotionFlaggedAt = now
     */
    public QuestionFrequencyEntry markForPromotion() {
        return new QuestionFrequencyEntry(
                city,
                category,
                procedureContextHash,
                eventContextHash,
                normalizedQuestion,
                hitCount,
                LocalDateTime.now(),
                firstSeen,
                lastSeen);
    }

    /**
     * Generate a unique key for this entry within a city's frequency map.
     * Format: {category}_{procedureContextHash}_{eventContextHash}_{normalizedQuestion}
     * 
     * @return A stable, deterministic key
     */
    public String getKey() {
        return String.format("%s_%d_%d_%s",
                category.name(),
                procedureContextHash,
                eventContextHash,
                normalizedQuestion);
    }
}
