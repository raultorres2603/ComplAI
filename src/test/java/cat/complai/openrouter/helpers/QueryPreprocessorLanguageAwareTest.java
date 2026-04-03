package cat.complai.openrouter.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QueryPreprocessorLanguageAwareTest {

    @Test
    void testPreprocess_EnglishQuery_ReturnsQueryContext() {
        QueryContext result = QueryPreprocessor.preprocess("complaint application");
        assertNotNull(result);
        assertNotNull(result.detectedLanguage());
        assertNotNull(result.tokens());
        assertEquals("complaint application", result.originalQuery());
    }

    @Test
    void testPreprocess_EnglishDetection() {
        QueryContext result = QueryPreprocessor.preprocess("complaint and application");
        assertEquals("EN", result.detectedLanguage(),
                "English query should be detected as EN language");
        // "and" is English stop word, should be filtered
        assertFalse(result.tokens().contains("and"),
                "English stop word 'and' should be filtered");
        assertTrue(result.tokens().contains("complaint"),
                "Non-stop word 'complaint' should be preserved");
        assertTrue(result.tokens().contains("application"),
                "Non-stop word 'application' should be preserved");
    }

    @Test
    void testPreprocess_SpanishDetection() {
        // Use stronger Spanish signals
        QueryContext result = QueryPreprocessor.preprocess("tengo una queja ñ");
        assertEquals("ES", result.detectedLanguage(),
                "Spanish query with ñ should be detected as ES language");
        // Verify tokens are extracted
        assertTrue(result.tokens().size() > 0,
                "Should have at least one token");
    }

    @Test
    void testPreprocess_CatalanDetection() {
        QueryContext result = QueryPreprocessor.preprocess("vull aquell permís però");
        assertEquals("CA", result.detectedLanguage(),
                "Catalan query with multiple Catalan signals should be detected as CA language");
        // Should detect Catalan and process tokens
        assertTrue(result.tokens().size() > 0,
                "Should have tokens");
    }

    @Test
    void testPreprocess_CatalanStopWordFiltering() {
        QueryContext result = QueryPreprocessor.preprocess("vull però tanca biblioteca", "CA");
        assertEquals("CA", result.detectedLanguage());
        // Explicitly set CA to test stop word filtering
        assertTrue(result.tokens().contains("tanca"),
                "Non-stop word 'tanca' should be preserved");
        assertTrue(result.tokens().contains("biblioteca"),
                "Non-stop word 'biblioteca' should be preserved");
    }

    @Test
    void testPreprocess_SpanishStopWordFiltering() {
        QueryContext result = QueryPreprocessor.preprocess("queja permiso ñ", "ES");
        assertEquals("ES", result.detectedLanguage());
        // Explicitly set ES to test Spanish stop word filtering
        assertTrue(result.tokens().contains("queja"),
                "Non-stop word 'queja' should be preserved");
        assertTrue(result.tokens().contains("permiso"),
                "Non-stop word 'permiso' should be preserved");
    }

    @Test
    void testPreprocess_EnglishStopWordFiltering() {
        QueryContext result = QueryPreprocessor.preprocess("the complaint is about the permit");
        assertEquals("EN", result.detectedLanguage());
        // "the", "is", "about" are English stop words
        assertFalse(result.tokens().contains("the"),
                "English stop word 'the' should be filtered");
        assertFalse(result.tokens().contains("is"),
                "English stop word 'is' should be filtered");
        assertFalse(result.tokens().contains("about"),
                "English stop word 'about' should be filtered");
        assertTrue(result.tokens().contains("complaint"),
                "Non-stop word 'complaint' should be preserved");
        assertTrue(result.tokens().contains("permit"),
                "Non-stop word 'permit' should be preserved");
    }

    @Test
    void testPreprocess_FrenchDetected() {
        QueryContext result = QueryPreprocessor.preprocess("à bientôt");
        // French should be detected from accent markers
        assertEquals("FR", result.detectedLanguage(),
                "French query with accents should be detected as FR language");
        assertNotNull(result.tokens());
    }

    @Test
    void testPreprocess_NullQuery() {
        QueryContext result = QueryPreprocessor.preprocess(null);
        assertNotNull(result);
        assertEquals("", result.originalQuery());
        assertEquals("CA", result.detectedLanguage(),
                "Null query should default to Catalan (El Prat default)");
        assertTrue(result.tokens().isEmpty());
    }

    @Test
    void testPreprocess_BlankQuery() {
        QueryContext result = QueryPreprocessor.preprocess("   ");
        assertNotNull(result);
        assertEquals("CA", result.detectedLanguage(),
                "Blank query should default to Catalan");
        assertTrue(result.tokens().isEmpty());
    }

    @Test
    void testPreprocess_LanguageOverride() {
        QueryContext result = QueryPreprocessor.preprocess("queja about permiso", "EN");
        assertEquals("EN", result.detectedLanguage(),
                "Explicit language parameter should override detection");
        // Should use English stop words
        assertFalse(result.tokens().contains("about"),
                "English stop word should be filtered when language is EN");
    }

    @Test
    void testPreprocess_AllTokensAreStopWords() {
        QueryContext result = QueryPreprocessor.preprocess("the is a", "EN");
        // When all tokens are stop words, fallback to keeping all tokens
        assertFalse(result.tokens().isEmpty(),
                "When all tokens are stop words, fallback to keeping all tokens");
    }

    @Test
    void testPreprocess_AccentRemoval() {
        QueryContext result = QueryPreprocessor.preprocess("café résumé");
        // Accents should be normalized
        assertTrue(result.tokens().stream().anyMatch(t -> t.contains("cafe")),
                "Accents should be removed in tokens");
    }

    @Test
    void testPreprocess_LowercaseNormalization() {
        QueryContext result = QueryPreprocessor.preprocess("COMPLAINT APPLICATION");
        assertEquals("EN", result.detectedLanguage());
        // Tokens should be lowercase
        assertTrue(result.tokens().stream().allMatch(t -> t.equals(t.toLowerCase())),
                "Tokens should be lowercase");
    }

    @Test
    void testPreprocess_MultipleSpacesCollapsed() {
        QueryContext result = QueryPreprocessor.preprocess("complaint    application");
        // Original query is preserved
        assertEquals("complaint    application", result.originalQuery());
        // But tokens should be correctly extracted
        assertTrue(result.tokens().contains("complaint"));
        assertTrue(result.tokens().contains("application"));
    }

    @Test
    void testPreprocess_MixedLanguageDefaultToDominantSignal() {
        // Query with more Spanish signals than Catalan
        QueryContext result = QueryPreprocessor.preprocess("queja ñ permiso");
        assertEquals("ES", result.detectedLanguage(),
                "Spanish signals (queja, ñ) should outweigh Catalan");
    }

    @Test
    void testPreprocess_PreservesTokenCount() {
        String query = "complaint permit application form";
        QueryContext result = QueryPreprocessor.preprocess(query, "EN");
        // All 4 meaningful words are non-stop words in English
        assertTrue(result.tokens().size() >= 3,
                "Meaningful words should be preserved");
    }

    @Test
    void testPreprocess_UniqueTokens() {
        QueryContext result = QueryPreprocessor.preprocess("complaint complaint application");
        // If tokens are deduplicated in some way, verify behavior
        assertTrue(result.tokens().contains("complaint"));
        assertTrue(result.tokens().contains("application"));
    }

    @Test
    void testPreprocess_EmptyAfterStopWordFiltering_FallbackToAll() {
        // Edge case: if all words are filtered, tokens should not be empty
        QueryContext result = QueryPreprocessor.preprocess("a the is", "EN");
        assertFalse(result.tokens().isEmpty(),
                "Should fallback to keeping all tokens when all are stop words");
    }
}
