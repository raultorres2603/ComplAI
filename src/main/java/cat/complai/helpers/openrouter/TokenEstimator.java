package cat.complai.helpers.openrouter;

/**
 * Simple token estimation utility for AI response processing.
 *
 * <p>Estimates token count from text length using the standard approximation
 * of 1 token ≅ 4 characters. This is a rough estimate used for logging and
 * metric purposes only — actual token usage is reported by the AI provider.
 */
public final class TokenEstimator {

    private TokenEstimator() {
        // Utility class — prevent instantiation
    }

    /**
     * Estimates the number of tokens in the given text.
     *
     * @param text the text to estimate (may be null or empty)
     * @return estimated token count, at minimum 1 for non-empty text, 0 for null/empty
     */
    public static int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }
}
