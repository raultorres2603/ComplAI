package cat.complai.openrouter.helpers.rag;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class TokenNormalizer {

    private TokenNormalizer() {
    }

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
