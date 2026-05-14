package cat.complai.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;

/**
 * Configuration for civic vocabulary expansion feature.
 *
 * <p>
 * Properties are bound from application.properties with the prefix "rag.civic-vocabulary".
 *
 * <p>
 * Example application.properties:
 * {@code
 * rag.civic-vocabulary.enabled=${RAG_CIVIC_VOCABULARY_ENABLED:true}
 * }
 *
 * <p>
 * When enabled, the CivicVocabularyService expands user queries in English, Spanish,
 * or French by appending Catalan civic synonyms (e.g., "complaint" → "complaint reclamació denúncia"),
 * enabling cross-language retrieval from the Catalan-indexed knowledge base.
 */
@Introspected
@ConfigurationProperties("rag.civic-vocabulary")
public class CivicVocabularyConfig {

    /**
     * Whether civic vocabulary expansion is enabled.
     * Default: true (enabled)
     */
    private boolean enabled = true;

    /**
     * Constructs a CivicVocabularyConfig with default values.
     */
    public CivicVocabularyConfig() {
    }

    /**
     * Constructs a CivicVocabularyConfig with explicit enabled flag.
     *
     * @param enabled whether civic vocabulary expansion is enabled
     */
    public CivicVocabularyConfig(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns whether civic vocabulary expansion is enabled.
     *
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether civic vocabulary expansion is enabled.
     *
     * @param enabled true to enable, false to disable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        return "CivicVocabularyConfig{enabled=" + enabled + "}";
    }
}