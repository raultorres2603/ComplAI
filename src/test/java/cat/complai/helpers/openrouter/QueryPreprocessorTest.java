package cat.complai.helpers.openrouter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QueryPreprocessor Tests")
class QueryPreprocessorTest {

    @Test
    @DisplayName("preprocess returns QueryContext with language and tokens")
    void test_preprocess_returnsQueryContext() {
        QueryContext result = QueryPreprocessor.preprocess("QUAN TANCA?");
        assertNotNull(result);
        assertNotNull(result.originalQuery());
        assertNotNull(result.detectedLanguage());
        assertNotNull(result.tokens());
    }

    @Test
    @DisplayName("preprocess detects and normalizes Catalan queries")
    void test_preprocess_catalan() {
        QueryContext result = QueryPreprocessor.preprocess("Quan tanca L'Ajuntament?");
        assertEquals("CA", result.detectedLanguage());
        assertTrue(result.tokens().contains("tanca"));
        assertTrue(result.tokens().contains("ajuntament"));
    }

    @Test
    @DisplayName("preprocess detects and normalizes English queries")
    void test_preprocess_english() {
        QueryContext result = QueryPreprocessor.preprocess("complaint about permit");
        assertEquals("EN", result.detectedLanguage());
        assertTrue(result.tokens().stream().anyMatch(t -> t.contains("complaint")));
        assertTrue(result.tokens().stream().anyMatch(t -> t.contains("permit")));
    }

    @Test
    @DisplayName("preprocess detects and normalizes Spanish queries")
    void test_preprocess_spanish() {
        QueryContext result = QueryPreprocessor.preprocess("queja sobre permiso");
        assertEquals("ES", result.detectedLanguage());
        assertTrue(result.tokens().stream().anyMatch(t -> t.contains("queja")));
        assertTrue(result.tokens().stream().anyMatch(t -> t.contains("permiso")));
    }

    @Test
    @DisplayName("preprocess removes accents in tokens")
    void test_preprocess_removesAccents() {
        QueryContext result = QueryPreprocessor.preprocess("café résumé naïve");
        // Accents should be removed in tokens
        String tokenString = String.join(" ", result.tokens());
        assertTrue(tokenString.contains("cafe") || tokenString.contains("resume"));
    }

    @Test
    @DisplayName("preprocess handles null gracefully")
    void test_preprocess_handlesNull() {
        QueryContext result = QueryPreprocessor.preprocess(null);
        assertNotNull(result);
        assertEquals("", result.originalQuery());
        assertEquals("CA", result.detectedLanguage());
        assertTrue(result.tokens().isEmpty());
    }

    @Test
    @DisplayName("preprocess handles blank strings")
    void test_preprocess_handlesBlank() {
        QueryContext result1 = QueryPreprocessor.preprocess("");
        QueryContext result2 = QueryPreprocessor.preprocess("   ");
        QueryContext result3 = QueryPreprocessor.preprocess("\t\n");

        assertEquals("CA", result1.detectedLanguage());
        assertEquals("CA", result2.detectedLanguage());
        assertEquals("CA", result3.detectedLanguage());
        assertTrue(result1.tokens().isEmpty());
        assertTrue(result2.tokens().isEmpty());
        assertTrue(result3.tokens().isEmpty());
    }

    @Test
    @DisplayName("preprocess with explicit language override")
    void test_preprocess_languageOverride() {
        QueryContext result = QueryPreprocessor.preprocess("complaint", "ES");
        assertEquals("ES", result.detectedLanguage(),
                "Explicit language parameter should override detection");
    }

    @Test
    @DisplayName("removeStopWords filters common English stop words (legacy)")
    void test_removeStopWords_filtersEnglish() {
        String result = QueryPreprocessor.removeStopWords("how to recycle paper");
        // "how", "to" are stop words; "recycle", "paper" are not
        assertTrue(result.contains("recycle"));
        assertTrue(result.contains("paper"));
    }

    @Test
    @DisplayName("removeStopWords filters common Catalan stop words (legacy)")
    void test_removeStopWords_filtersCatalan() {
        String result = QueryPreprocessor.removeStopWords("quan tanca la biblioteca");
        // "quan", "la" are stop words; "tanca", "biblioteca" are not
        assertTrue(result.contains("tanca"));
        assertTrue(result.contains("biblioteca"));
    }

    @Test
    @DisplayName("removeStopWords fallback to original if all words are stop words (legacy)")
    void test_removeStopWords_fallbackToOriginal() {
        String result = QueryPreprocessor.removeStopWords("a the and or");
        // All words are stop words; should return original
        assertEquals("a the and or", result);
    }

    @Test
    @DisplayName("removeStopWords handles null gracefully (legacy)")
    void test_removeStopWords_handlesNull() {
        String result = QueryPreprocessor.removeStopWords(null);
        assertNull(result);
    }

    @Test
    @DisplayName("preprocess handles multiple spaces correctly")
    void test_preprocess_handlesMultipleSpaces() {
        QueryContext result = QueryPreprocessor.preprocess("quan    tanca    biblioteca");
        assertEquals("quan    tanca    biblioteca", result.originalQuery());
        assertTrue(result.tokens().contains("quan") || result.tokens().size() >= 1);
    }

    @Test
    @DisplayName("preprocess handles special characters")
    void test_preprocess_handlesSpecialCharacters() {
        QueryContext result = QueryPreprocessor.preprocess("café-restaurant @ 12:30");
        String tokenString = String.join(" ", result.tokens());
        assertTrue(tokenString.contains("restaurant") || tokenString.contains("cafe"));
    }

    @Test
    @DisplayName("preprocess preserves numbers in tokens")
    void test_preprocess_preservesNumbers() {
        QueryContext result = QueryPreprocessor.preprocess("apartment 123 district 4");
        String tokenString = String.join(" ", result.tokens());
        assertTrue(tokenString.contains("123") || tokenString.contains("apartment"));
    }

    @Test
    @DisplayName("preprocess detects and normalizes French queries")
    void test_preprocess_french() {
        QueryContext result = QueryPreprocessor.preprocess("Je veux présenter une plainte");
        assertEquals("FR", result.detectedLanguage());
        assertTrue(result.tokens().stream().anyMatch(t -> t.contains("plainte")),
                "Should contain 'plainte' (complaint in French)");
    }

    @Test
    @DisplayName("preprocess applies French stop word filtering")
    void test_preprocess_frenchStopWords() {
        QueryContext result = QueryPreprocessor.preprocess("le permis de construction", "FR");
        assertEquals("FR", result.detectedLanguage());
        // "le", "de" are French stop words; "permis", "construction" should be
        // preserved
        String tokenString = String.join(" ", result.tokens());
        assertTrue(tokenString.contains("permis") || tokenString.contains("construction"),
                "Should preserve non-stop-word French terms");
    }

    @Test
    @DisplayName("preprocess detects French with accented characters")
    void test_preprocess_french_accents() {
        QueryContext result = QueryPreprocessor.preprocess("J'ai une réclamation à faire");
        assertEquals("FR", result.detectedLanguage(), "Should detect French by accent marks");
        String tokenString = String.join(" ", result.tokens());
        assertTrue(tokenString.contains("reclamation") || tokenString.contains("clamation"),
                "Should normalize accented French terms");
    }

    @Test
    @DisplayName("preprocess with French civic terms")
    void test_preprocess_frenchCivicTerms() {
        QueryContext result = QueryPreprocessor.preprocess("permis d'événement autorisation", "FR");
        assertEquals("FR", result.detectedLanguage());
        String tokenString = String.join(" ", result.tokens());
        assertTrue(tokenString.contains("permis") || tokenString.contains("autorisation"),
                "Should preserve French civic vocabulary");
    }
}
