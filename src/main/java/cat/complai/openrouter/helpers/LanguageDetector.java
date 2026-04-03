package cat.complai.openrouter.helpers;

import java.util.Locale;

/**
 * Heuristic language detector for the four languages supported by the Gall Potablava assistant:
 * Catalan (CA), Spanish (ES), English (EN), and French (FR).
 *
 * Uses a simple signal-counting approach: Catalan has distinctive markers (l·l, stop-words
 * absent in Spanish) that are checked first. The ñ character uniquely identifies Spanish.
 * French is identified by accented marker characters (ç, û, ô, é, è, ê, à) and French-specific
 * keywords. English is the fallback when neither Catalan nor Spanish signals appear.
 *
 * This is intentionally a lightweight heuristic — not a general-purpose NLP library.
 * It is designed for short citizen-facing messages from El Prat residents, where
 * Catalan is the most common language and the false-positive rate is acceptably low.
 *
 * Returns ISO 639-1 codes in uppercase: "CA", "ES", "EN", or "FR".
 */
public final class LanguageDetector {

    // Markers that are distinctive to Catalan and absent (or very rare) in Spanish.
    private static final String[] CATALAN_SIGNALS = {
            "l·l", "·l",
            " vull ", " però ", " perquè ", " és ", " amb ",
            " quan ", " aquest ", " aquell ", " nosaltres", " vosaltres",
            " podeu ", " esteu ", " sou ", " heu ", " molt ",
            " volem ", " tenim ", " feu ", " cal ",
    };

    // Markers that are distinctive to Spanish.
    private static final String[] SPANISH_SIGNALS = {
            "ñ",
            " quiero ", " tengo ", " usted ", " señor ", " señora ",
            " también ", " cuando ", " porque ", " estoy ",
            " vecino ", " necesito ", " solicito ", " quejas ",
            " queremos ", " tenemos ", " hacemos ",
            " queja ", " permiso ", " reclamacion ", " denuncia ",
            " solicitud ", " autorizacion ", " formulario ",
    };

    // Markers that are distinctive to French: accented characters and French keywords.
    private static final String[] FRENCH_SIGNALS = {
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

        int catalanScore = countSignals(lower, CATALAN_SIGNALS);
        int spanishScore = countSignals(lower, SPANISH_SIGNALS);
        int frenchScore = countSignals(lower, FRENCH_SIGNALS);

        if (catalanScore > 0 && catalanScore >= spanishScore && catalanScore >= frenchScore) return "CA";
        if (spanishScore > 0 && spanishScore >= frenchScore) return "ES";
        if (frenchScore > 0) return "FR";
        return "EN";
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

