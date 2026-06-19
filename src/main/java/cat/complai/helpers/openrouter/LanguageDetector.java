package cat.complai.helpers.openrouter;

import java.util.List;
import java.util.Locale;

/**
 * Heuristic language detector for the four languages supported by the Gall Potablava assistant:
 * Catalan (CA), Spanish (ES), English (EN), and French (FR).
 *
 * <p>Uses a Strategy pattern internally: each supported language contributes a
 * {@link LanguageStrategy} that knows its own signal words. New languages can be added
 * by implementing {@link LanguageStrategy} and adding it to the {@code STRATEGIES} list
 * without modifying the detection logic.
 *
 * <p>This is intentionally a lightweight heuristic — not a general-purpose NLP library.
 * It is designed for short citizen-facing messages from El Prat residents, where
 * Catalan is the most common language and the false-positive rate is acceptably low.
 *
 * <p>Returns ISO 639-1 codes in uppercase: "CA", "ES", "EN", or "FR".
 */
public final class LanguageDetector {

    /**
     * Strategy interface for a single language's signal-word detection.
     * Implementations provide the language code and a list of signal substrings
     * that, when found in lowercased text, contribute to the language's score.
     */
    public interface LanguageStrategy {
        /** ISO 639-1 code in uppercase, e.g. "CA", "ES", "FR". */
        String getLanguageCode();
        /** Signal substrings to search for (already lowercased). */
        String[] getSignals();
    }

    // --- Built-in strategies -------------------------------------------------

    private static final LanguageStrategy CATALAN = new LanguageStrategy() {
        @Override
        public String getLanguageCode() { return "CA"; }
        @Override
        public String[] getSignals() {
            return new String[] {
                    "l·l", "·l",
                    " vull ", " però ", " perquè ", " és ", " amb ",
                    " quan ", " aquest ", " aquell ", " nosaltres", " vosaltres",
                    " podeu ", " esteu ", " sou ", " heu ", " molt ",
                    " volem ", " tenim ", " feu ", " cal ",
            };
        }
    };

    private static final LanguageStrategy SPANISH = new LanguageStrategy() {
        @Override
        public String getLanguageCode() { return "ES"; }
        @Override
        public String[] getSignals() {
            return new String[] {
                    "ñ",
                    " quiero ", " tengo ", " usted ", " señor ", " señora ",
                    " también ", " cuando ", " porque ", " estoy ",
                    " vecino ", " necesito ", " solicito ", " quejas ",
                    " queremos ", " tenemos ", " hacemos ",
                    " queja ", " permiso ", " reclamacion ", " denuncia ",
                    " solicitud ", " autorizacion ", " formulario ",
            };
        }
    };

    private static final LanguageStrategy FRENCH = new LanguageStrategy() {
        @Override
        public String getLanguageCode() { return "FR"; }
        @Override
        public String[] getSignals() {
            return new String[] {
                    "ç", "û", "ô", "é", "è", "ê", "à", "ù", "î", "ï", "ö", "ü",
                    " vous ", "-vous", "vous ",
                    " je ", " tu ", " nous ",
                    " était ", " français ", " comment ", " pourquoi ",
                    " bonjour ", " bonsoir ", " merci ", " s'il ", " plaît ",
                    " monsieur ", " madame ", " mademoiselle ",
                    " aller ", " avoir ", " être ", " faire ", " pouvoir ", " vouloir ", " devoir ",
                    " plainte ", " reclamation ", " demande ", " clamation",
                    " formulaire ", " autorisation ", " permis ", " presentation",
                    "pouvez", "peux", "pouvaient",
            };
        }
    };

    private static final List<LanguageStrategy> STRATEGIES = List.of(CATALAN, SPANISH, FRENCH);

    private LanguageDetector() {}

    /**
     * Detects the language of the given text.
     *
     * @param text the input text; may be null or blank
     * @return "CA" for Catalan, "ES" for Spanish, "FR" for French, "EN" for English (default)
     */
    public static String detect(String text) {
        if (text == null || text.isBlank()) {
            // El Prat default: residents are predominantly Catalan speakers.
            return "CA";
        }
        // Pad with spaces so word-boundary checks work for words at the start or end of input.
        String lower = " " + text.toLowerCase(Locale.ROOT) + " ";

        String best = "EN";
        int bestScore = 0;

        for (LanguageStrategy strategy : STRATEGIES) {
            int score = countSignals(lower, strategy.getSignals());
            if (score > 0 && score > bestScore) {
                bestScore = score;
                best = strategy.getLanguageCode();
            }
        }

        return best;
    }

    private static int countSignals(String lower, String[] signals) {
        int count = 0;
        for (String signal : signals) {
            if (lower.contains(signal)) {
                count++;
            }
        }
        return count;
    }
}

