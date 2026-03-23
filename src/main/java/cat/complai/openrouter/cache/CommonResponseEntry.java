package cat.complai.openrouter.cache;

import io.micronaut.core.annotation.Introspected;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for pre-configured common AI responses loaded from
 * common-ai-requests.json.
 * 
 * Used to pre-populate ResponseCacheService with 30-50 high-frequency responses
 * at application startup (Tier 2 cache).
 * 
 * Example JSON:
 * {
 * "category": "PARKING",
 * "city": "elprat",
 * "question_template": "How do I get a parking permit?",
 * "response": "To get a parking permit at El Prat, you need to..."
 * }
 * 
 * City can be null to apply response across all cities (lower priority in
 * matches).
 * Question template is used for simple pattern matching or as documentation
 * only.
 */
@Introspected
public record CommonResponseEntry(
        QuestionCategory category,
        String city,
        @JsonProperty("question_template") String questionTemplate,
        String response) {
    /**
     * Compact constructor validates fields.
     */
    public CommonResponseEntry {
        if (category == null) {
            throw new IllegalArgumentException("category cannot be null");
        }
        if (response == null || response.isBlank()) {
            throw new IllegalArgumentException("response cannot be null or blank");
        }
    }
}
