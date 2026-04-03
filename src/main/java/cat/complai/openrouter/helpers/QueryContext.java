package cat.complai.openrouter.helpers;

import java.util.List;

/**
 * Immutable context object that passes language information and preprocessed
 * tokens through the query preprocessing pipeline.
 *
 * <p>
 * Carries the original query, detected language, and normalized/filtered tokens
 * so that downstream services (RAG, prompt builders) can make language-aware
 * decisions.
 */
public record QueryContext(
        String originalQuery,
        String detectedLanguage,
        List<String> tokens
) {
    /**
     * @param originalQuery the raw input query from the user
     * @param detectedLanguage ISO language code: "CA", "ES", or "EN" (never null)
     * @param tokens normalized and stop-word-filtered tokens (may be empty)
     */
    public QueryContext {
        if (detectedLanguage == null) {
            throw new IllegalArgumentException("detectedLanguage cannot be null");
        }
    }
}
