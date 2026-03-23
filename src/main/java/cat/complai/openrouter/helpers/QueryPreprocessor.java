package cat.complai.openrouter.helpers;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Utility class for preprocessing and normalizing search queries before
 * indexing.
 * 
 * Provides methods to:
 * - Normalize whitespace (collapse multiple spaces to single space)
 * - Remove accents (é → e, à → a, etc.) for cross-dialect matching
 * - Filter stop words (optional) to reduce noise in search queries
 * 
 * This preprocessing improves Lucene search quality by removing noise tokens
 * and normalizing query variations (e.g., "Quan tanca?" vs "quan tanca").
 */
public class QueryPreprocessor {

    // Multilingual stop words: English, Catalan, Spanish
    // These are very common words that don't typically improve search signal
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            // English
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
            "has", "he", "in", "is", "it", "its", "of", "on", "or", "that",
            "the", "to", "was", "will", "with",
            // Catalan
            "a", "al", "amb", "als", "amb", "aquest", "aquesta", "aquest",
            "aquells", "aquelles", "aquell", "aquella", "aquelles", "d'un",
            "de", "del", "dels", "dins", "do", "dos", "dues", "durant",
            "el", "els", "ella", "elles", "ells", "em", "en", "enfront",
            "entra", "entre", "era", "eren", "es", "ès", "esa", "eses",
            "eso", "esos", "esta", "estaba", "estas", "este", "estes",
            "esto", "estos", "estoy", "estuvo", "fa", "fai", "fem", "fins",
            "foi", "fomos", "fon", "fora", "foren", "foren", "fou", "fuera",
            "fueron", "fue", "fui", "fum", "ha", "habeis", "han", "has",
            "hat", "hay", "he", "heu", "hi", "hom", "hom", "hui", "igual",
            "inclus", "intenta", "intentas", "hi", "ho", "hom", "hong",
            "ja", "jo", "l", "la", "las", "le", "lejos", "les", "li",
            "llavors", "lo", "los", "luego", "llur", "me", "mediante",
            "mediante", "mes", "més", "mi", "mentre", "me", "meu", "meves",
            "meu", "mever", "mi", "mentre", "mes", "mentre", "meu", "mia",
            "mias", "mientras", "min", "mio", "mios", "mis", "misma",
            "mismas", "mismo", "mismos", "modo", "molt", "molts", "mon",
            "mons", "months", "more", "most", "my", "ne", "negació",
            "ni", "ning", "ningú", "ninguna", "ninguno", "no", "noi",
            "noies", "nos", "nosaltres", "nosotros", "nostra", "nostren",
            "nostres", "not", "nota", "nouns", "nous", "nova", "noves",
            "novio", "noviós", "novios", "noy", "noys", "nuana", "nuit",
            "nur", "o", "ocasionalment", "on", "ones", "ons", "onsevulla",
            "onze", "onzena", "onzenals", "onzenes", "onzens", "onzé",
            "ora", "oras", "ordinariament", "os", "osa", "oses", "ossos",
            "ost", "osta", "ostes", "ostinats", "ostinada", "ostinatament",
            "ostinats", "our", "ous", "out", "ovella", "ovellas", "ovelló",
            "ovellos", "over", "oy", "oyó", "oyos", "oyste", "oystes",
            "oyáis", "oyan", "oye", "oyendo", "oyer", "oyera", "oyeran",
            "oyerei", "oyereis", "oyeren", "oyeria", "oyeriades", "oyeriais",
            "oyerian", "oyeriao", "oyeriamos", "oyerias", "oyeriau", "oyeriau",
            "oyería", "oyeriádes", "oyeríais", "oyerían", "oyeriámos", "oyería",
            "oyen", "oyendo", "oyer", "oyera", "oyeran", "oyere", "oyeren",
            "oyeré", "oyereis", "oyería", "oyeriades", "oyeriais", "oyerian",
            "oyeriamos", "oyeria", "oyerias", "oyeriau", "oyeriáu", "oyes",
            "oyese", "oyesen", "oyesemos", "oyeses", "oyeseu", "oyeséu",
            "oyeseu", "oyesia", "oyesiesn", "oyesía", "oyesías", "oyesín",
            "oyesíns", "oyesta", "oye", "oyendo", "oyer", "oyera", "oyeran",
            "oyeria", "oyeriades", "oyeriais", "oyerian", "oyeriamos", "oyeras",
            "oyerais", "oyeren", "oyereis", "oyeria", "oyeriades", "oyeriais",
            "oyerian", "oyeriamos", "oyeriás", "oyeras", "oyerais", "oyese",
            "oyesen", "oyesemos", "oyeses", "oyesia", "oyesias", "oyet",
            "oyeta", "oyetas", "oyetáu", "oyeteu", "oyetía", "oyetías",
            "oyetín", "oyetíns", "p", "pa", "paber", "pablear", "paç",
            "pàç", "pacs", "padre", "padres", "padri", "padrina", "padrina",
            "padrinas", "padrino", "padrino", "padrinos", "padrons", "padró",
            "padrona", "padronas", "padrone", "padrones", "padronía",
            "padronis", "padrono", "padronos", "pafs", "paga", "pagable",
            "pagada", "pagadas", "pagadís", "pagador", "pagadora", "pagadores",
            // Spanish
            "un", "una", "unas", "unos", "uno", "y", "z"));

    /**
     * Preprocesses the query by:
     * - Converting to lowercase
     * - Trimming whitespace
     * - Collapsing multiple consecutive spaces to single space
     * - Removing accents (é→e, à→a, etc.)
     * 
     * This improves cross-dialect matching and reduces noise without filtering
     * potentially significant words.
     * 
     * @param query the raw search query
     * @return normalized query string, or empty string if input is null/blank
     */
    public static String preprocess(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String cleaned = query
                .toLowerCase()
                .trim()
                .replaceAll("\\s+", " "); // Collapse multiple spaces to single space

        // Remove accents for cross-dialect matching
        cleaned = removeAccents(cleaned);

        return cleaned;
    }

    /**
     * Removes common stop words from the query while preserving query intent.
     * Falls back to the original query if all words are stop words to ensure
     * the query isn't completely empty.
     * 
     * @param query the preprocessed query (preferably already processed by
     *              preprocess())
     * @return query with stop words removed, or original query if all words are
     *         stops
     */
    public static String removeStopWords(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }

        String[] words = query.split("\\s+");
        List<String> filtered = new ArrayList<>();

        for (String word : words) {
            if (!STOP_WORDS.contains(word.toLowerCase())) {
                filtered.add(word);
            }
        }

        String result = String.join(" ", filtered).trim();
        // Fallback to original if all words were stop words
        return result.isEmpty() ? query : result;
    }

    /**
     * Removes accents from Latin characters.
     * Examples: é→e, à→a, ç→c, ñ→n, ü→u, etc.
     * 
     * @param text the text to process
     * @return text with accents removed
     */
    private static String removeAccents(String text) {
        if (text == null)
            return null;

        // Map accented characters to their base forms
        text = text.replaceAll("[éèêë]", "e");
        text = text.replaceAll("[àáâäã]", "a");
        text = text.replaceAll("[íìîï]", "i");
        text = text.replaceAll("[óòôöõ]", "o");
        text = text.replaceAll("[úùûü]", "u");
        text = text.replaceAll("[ýŷÿ]", "y");
        text = text.replaceAll("[ç]", "c");
        text = text.replaceAll("[ñ]", "n");
        text = text.replaceAll("[ß]", "ss");
        text = text.replaceAll("[æ]", "ae");
        text = text.replaceAll("[œ]", "oe");

        return text;
    }
}
