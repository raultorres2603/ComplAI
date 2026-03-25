package cat.complai.openrouter.helpers;

import java.util.regex.Pattern;

/**
 * Utility class for cleaning HTML responses by removing excessive newlines
 * around tag boundaries while preserving intentional content breaks.
 */
public class HtmlFormatter {

    private static final Pattern NEWLINES_BEFORE_TAG = Pattern.compile("\n+(</?[a-zA-Z][a-zA-Z0-9]*[^>]*>)");
    private static final Pattern CLOSING_TAG_TO_OPENING_TAG = Pattern.compile("(>)\n+(<)");

    /**
     * Cleans HTML by removing excessive newlines around tag boundaries.
     * 
     * Strategy:
     * 1. Remove newlines immediately before any tag
     * 2. Remove newlines between closing and opening tags
     * 3. Collapse multiple consecutive newlines to double newlines
     * 4. Strip leading/trailing whitespace
     * 
     * @param html The HTML string to clean (can be null or empty)
     * @return Cleaned HTML string, or the original if null/empty
     */
    public static String cleanHtml(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }

        String result = html;

        // Step 1: Remove newlines immediately before any tag (opening or closing)
        result = NEWLINES_BEFORE_TAG.matcher(result).replaceAll("$1");

        // Step 2: Remove newlines between directly adjacent closing and opening tags
        result = CLOSING_TAG_TO_OPENING_TAG.matcher(result).replaceAll("$1$2");

        // Step 3: Collapse multiple consecutive newlines to double newlines (preserve paragraph breaks)
        result = result.replaceAll("\n{3,}", "\n\n");

        // Step 4: Strip leading and trailing whitespace
        result = result.trim();

        return result;
    }
}
