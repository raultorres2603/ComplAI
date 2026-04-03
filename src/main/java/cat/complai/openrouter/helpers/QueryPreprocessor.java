package cat.complai.openrouter.helpers;

import cat.complai.openrouter.helpers.rag.TokenNormalizer;

import java.util.*;
import java.util.logging.Logger;

/**
 * Utility class for preprocessing and normalizing search queries before
 * indexing.
 *
 * Provides methods to:
 * - Detect the user's language (CA/ES/EN/FR) at the START of preprocessing
 * - Normalize whitespace (collapse multiple spaces to single space)
 * - Remove accents (é → e, à → a, etc.) for cross-dialect matching
 * - Filter stop words (language-aware) to reduce noise in search queries
 *
 * This preprocessing improves search quality by applying language-specific
 * filtering and normalizing query variations (e.g., "Quan tanca?" vs "quan
 * tanca").
 */
public class QueryPreprocessor {

    private static final Logger logger = Logger.getLogger(QueryPreprocessor.class.getName());

    // Language-specific stop words
    private static final Set<String> CATALAN_STOP_WORDS = new HashSet<>(Arrays.asList(
            "a", "al", "amb", "als", "aquest", "aquesta",
            "aquells", "aquelles", "aquell", "aquella", "aquelles", "d'un",
            "de", "del", "dels", "dins", "do", "dos", "dues", "durant",
            "el", "els", "ella", "elles", "ells", "em", "en", "enfront",
            "entra", "entre", "era", "eren", "es", "ès", "esa", "eses",
            "eso", "esos", "esta", "estaba", "estas", "este", "estes",
            "esto", "estos", "estoy", "estuvo", "fa", "fai", "fem", "fins",
            "foi", "fomos", "fon", "fora", "foren", "fou", "fuera",
            "fueron", "fue", "fui", "fum", "ha", "habeis", "han", "has",
            "hat", "hay", "he", "heu", "hi", "hom", "hui", "igual",
            "inclus", "intenta", "intentas", "ho",
            "ja", "jo", "l", "la", "las", "le", "lejos", "les", "li",
            "llavors", "lo", "los", "luego", "llur", "me", "mediante",
            "mes", "més", "mi", "mentre", "meu", "meves",
            "mia", "mias", "mientras", "min", "mio", "mios", "mis", "misma",
            "mismas", "mismo", "mismos", "modo", "molt", "molts", "mon",
            "mons", "more", "most", "my", "ne", "negació",
            "ni", "ning", "ningú", "ninguna", "ninguno", "no", "noi",
            "noies", "nos", "nosaltres", "nosotros", "nostra", "nostren",
            "nostres", "not", "nota", "nous", "nova", "noves",
            "noy", "noys", "nuit", "o", "ocasionalment", "on", "ones", "ons",
            "onzena", "onze", "ora", "oras", "ordinariament", "os", "osa", "oses",
            "ost", "osta", "ostes", "ostinats", "ostinada", "ostinatament",
            "our", "ous", "out", "ovella", "ovellas", "ovelló",
            "ovellos", "over"));

    private static final Set<String> SPANISH_STOP_WORDS = new HashSet<>(Arrays.asList(
            "un", "una", "unas", "unos", "uno", "y", "z",
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
            "has", "he", "in", "is", "it", "its", "of", "on", "or", "that",
            "the", "to", "was", "will", "with",
            "el", "la", "de", "que", "y", "a", "en", "un", "ser", "se", "no",
            "haber", "por", "con", "su", "para", "es", "al", "lo", "como", "más",
            "o", "pero", "sus", "le", "ya", "o", "ésta", "sí", "porque", "esta",
            "como", "en", "para", "atras", "fue",
            "eres", "estaba", "estamos", "están", "estaras", "estaremos",
            "estará", "estarán", "estaría", "estarían", "estaríamos",
            "estuviese", "estuviesen", "estuvieras", "estuviéramos",
            "estuviesis", "estuviésemos"));

    private static final Set<String> ENGLISH_STOP_WORDS = new HashSet<>(Arrays.asList(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
            "has", "he", "in", "is", "it", "its", "of", "on", "or", "that",
            "the", "to", "was", "will", "with",
            "about", "above", "after", "again", "against", "all", "am", "any",
            "being", "below", "between", "both", "but", "can", "could",
            "did", "do", "does", "doing", "down", "during", "each",
            "few", "further", "had", "have", "having", "here", "hers",
            "herself", "him", "himself", "his", "how", "i", "if", "just",
            "me", "might", "more", "most", "my", "myself", "no", "nor", "not",
            "only", "out", "over", "own", "same", "should", "so", "some",
            "such", "than", "then", "them", "themselves", "there", "these",
            "they", "this", "those", "through", "too", "under", "until",
            "up", "very", "we", "were", "what", "when", "where", "which",
            "while", "who", "whom", "why", "you", "your", "yours", "yourself",
            "yourselves"));

    private static final Set<String> FRENCH_STOP_WORDS = new HashSet<>(Arrays.asList(
            "le", "la", "les", "un", "une", "des", "de", "d", "du", "et", "ou", "mais",
            "donc", "car", "que", "qui", "quoi", "comment", "où", "quand", "pourquoi",
            "combien", "quel", "quelle", "quels", "quelles", "celui", "celà", "ce",
            "cette", "cet", "ceux", "ces", "son", "sa", "ses", "notre", "nos", "votre",
            "vos", "leur", "leurs", "je", "tu", "il", "elle", "nous", "vous", "ils",
            "elles", "moi", "toi", "lui", "nous", "vous", "eux", "elles", "être",
            "avoir", "aller", "faire", "pouvoir", "vouloir", "devoir", "falloir",
            "sembler", "devenir", "penser", "savoir", "prendre", "trouver", "donner",
            "mettre", "venir", "montrer", "voir", "laisser", "garder", "demander",
            "croire", "rester", "durer", "craindre", "attendre", "changer", "tomber",
            "quitter", "reconnaître", "connaître", "répondre", "tenir", "chercher",
            "noter", "entendre", "partir", "achever", "cacher", "cela", "ceci", "près",
            "loin", "avant", "après", "contre", "avec", "sans", "chez", "par", "pour",
            "vers", "depuis", "jusque", "pendant", "sous", "sur", "entre", "dans",
            "hors", "malgré", "selon", "aucun", "aucune", "quelque", "quelques",
            "certain", "certaine", "certains", "certaines", "divers", "diverse",
            "différent", "différente", "autre", "autres", "même", "mêmes", "bon",
            "bonne", "mauvais", "mauvaise", "grand", "grande", "petit", "petite",
            "nouveau", "nouvelle", "vieux", "vieille", "jeune", "ancien", "ancienne",
            "premier", "première", "dernier", "dernière", "seul", "seule", "seuls",
            "seules", "tout", "toute", "tous", "toutes", "à", "a", "afin", "ainsi",
            "alors", "assez", "attendu", "aucun", "aucune", "aura", "aurai", "auraient",
            "aurais", "aurait", "auras", "aurez", "auries", "aurions", "aurois",
            "avoue", "ayant", "b", "bah", "bas", "basee", "bat", "beau", "beaucoup",
            "because"));

    // Backward-compatible combined set (now deprecated, use language-specific sets)
    @Deprecated
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
            "has", "he", "in", "is", "it", "its", "of", "on", "or", "that",
            "the", "to", "was", "will", "with",
            "un", "una", "unas", "unos", "uno", "y", "z"));

    private QueryPreprocessor() {
    }

    /**
     * Preprocesses the query with early language detection.
     * Returns a {@code QueryContext} containing:
     * - Original query
     * - Detected language (CA, ES, EN, FR)
     * - Normalized and language-specific stop-word-filtered tokens
     *
     * @param query the raw search query (may be null or blank)
     * @return {@code QueryContext} with language detected and tokens processed
     */
    public static QueryContext preprocess(String query) {
        return preprocess(query, null);
    }

    /**
     * Preprocesses the query with explicit language override.
     * If {@code language} is null, detects language automatically.
     *
     * @param query    the raw search query (may be null or blank)
     * @param language explicit language code ("CA", "ES", "EN"), or null to
     *                 auto-detect
     * @return {@code QueryContext} with language and tokens processed
     */
    public static QueryContext preprocess(String query, String language) {
        if (query == null || query.isBlank()) {
            return new QueryContext("", "CA", Collections.emptyList());
        }

        String detectedLanguage = language != null ? language : LanguageDetector.detect(query);

        // Normalize: remove accents, lowercase, collapse whitespace
        String normalized = TokenNormalizer.normalizeForSearch(query);

        // Tokenize
        List<String> tokens = TokenNormalizer.tokenize(normalized);

        // Filter language-specific stop words
        Set<String> stopWordsForLanguage = getStopWordsForLanguage(detectedLanguage);
        List<String> filtered = new ArrayList<>();
        for (String token : tokens) {
            if (!stopWordsForLanguage.contains(token.toLowerCase())) {
                filtered.add(token);
            }
        }

        // Fallback to all tokens if all were filtered
        List<String> finalTokens = filtered.isEmpty() ? tokens : filtered;

        String logMsg = "QueryPreprocessor: query='" + query +
                "' language='" + detectedLanguage + "' tokens=" + finalTokens.size();
        logger.fine(logMsg);

        return new QueryContext(query, detectedLanguage, List.copyOf(finalTokens));
    }

    /**
     * Returns the language-specific stop words set for the given language.
     * Falls back to English stop words for unknown/unsupported languages.
     */
    private static Set<String> getStopWordsForLanguage(String language) {
        if (language == null) {
            language = "CA";
        }
        return switch (language.toUpperCase()) {
            case "CA" -> CATALAN_STOP_WORDS;
            case "ES" -> SPANISH_STOP_WORDS;
            case "EN" -> ENGLISH_STOP_WORDS;
            case "FR" -> FRENCH_STOP_WORDS;
            default -> ENGLISH_STOP_WORDS;
        };
    }

    /**
     * Removes common stop words from the query while preserving query intent.
     * Falls back to the original query if all words are stop words to ensure
     * the query isn't completely empty.
     *
     * @deprecated Use {@link #preprocess(String)} instead, which returns
     *             {@code QueryContext} with language-specific filtering.
     *
     * @param query the preprocessed query (preferably already processed by
     *              preprocess())
     * @return query with stop words removed, or original query if all words are
     *         stops
     */
    @Deprecated
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
}
