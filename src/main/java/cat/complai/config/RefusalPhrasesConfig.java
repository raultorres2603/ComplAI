package cat.complai.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;

import java.util.List;

/**
 * Configuration for AI refusal phrase detection.
 *
 * <p>
 * Properties are bound from application.properties with the prefix "refusal".
 * When no overrides are present, the built-in default phrases (covering English,
 * Catalan, and Spanish) are used.
 *
 * <p>
 * Example application.properties override:
 * {@code
 * refusal.phrases[0]=custom refusal phrase
 * refusal.phrases[1]=another refusal
 * }
 *
 * <p>
 * If any override is set, the entire list replaces the defaults — individual
 * entries are not merged.
 */
@Introspected
@ConfigurationProperties("refusal")
public class RefusalPhrasesConfig {

    /**
     * Default refusal phrases used when no config overrides are provided.
     * Covers English, Catalan, and Spanish patterns that indicate the AI
     * is refusing to answer because the request is off-topic for the city.
     */
    public static final List<String> DEFAULT_PHRASES = List.of(
            "can't help with",
            "cannot help with",
            "can't help",
            "cannot help",
            "can't assist",
            "cannot assist",
            "i'm sorry, i can't",
            "i'm sorry i can't",
            "i'm sorry, i cannot",
            "i'm sorry i cannot",
            "i cannot",
            "i can't",
            "i am unable to",
            "i'm unable to",
            "i cannot help",
            "i can't help",
            "cannot provide",
            "can't provide",
            "no puc ajudar",
            "no puc ajudar amb",
            "no puedo ayudar",
            "no puedo ayudar con",
            "lo siento, no puedo ayudar",
            "lo siento, no puedo",
            "no puedo",
            "no puc"
    );

    private List<String> phrases = DEFAULT_PHRASES;

    /**
     * Constructs a RefusalPhrasesConfig with default values.
     */
    public RefusalPhrasesConfig() {
    }

    /**
     * Returns the list of refusal phrases to detect.
     *
     * @return the refusal phrases (never null)
     */
    public List<String> getPhrases() {
        return phrases;
    }

    /**
     * Sets the list of refusal phrases.
     *
     * @param phrases the refusal phrases to use
     */
    public void setPhrases(List<String> phrases) {
        this.phrases = phrases;
    }
}
