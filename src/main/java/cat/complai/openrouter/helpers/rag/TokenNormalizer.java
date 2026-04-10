package cat.complai.openrouter.helpers.rag;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Text normalisation and tokenisation utilities for the in-memory RAG lexical index.
 *
 * <p>Normalisation pipeline:
 * <ol>
 *   <li>Lowercase (locale-independent via {@code Locale.ROOT})</li>
 *   <li>NFD Unicode decomposition followed by combining-character removal (accent folding:
 *       {@code é → e}, {@code à → a}, {@code ç → c}, {@code ·l → l}, etc.)</li>
 *   <li>Non-alphanumeric characters replaced with spaces</li>
 *   <li>Consecutive whitespace collapsed</li>
 * </ol>
 *
 * <p>This design maximises cross-dialect matching (Catalan, Spanish, English) without
 * requiring a language-specific stemmer.
 */
public final class TokenNormalizer {

    private TokenNormalizer() {
    }

    /**
     * Applies the full normalisation pipeline to raw text and returns a single normalised string.
     *
     * @param rawText the input text; returns an empty string if null or blank
     * @return the normalised, accent-folded, lowercased text with non-alphanumeric characters
     *         replaced by spaces
     */
    public static String normalizeForSearch(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        String lowerCased = rawText.toLowerCase(Locale.ROOT).trim();
        String accentFolded = Normalizer.normalize(lowerCased, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");

        return accentFolded
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    /**
     * Normalises the text and splits it into individual tokens.
     *
     * @param rawText the input text
     * @return an immutable list of non-blank normalised tokens; empty if the input is null or blank
     */
    public static List<String> tokenize(String rawText) {
        String normalized = normalizeForSearch(rawText);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(normalized.split(" "))
                .filter(token -> !token.isBlank())
                .collect(Collectors.toList());
    }
}
