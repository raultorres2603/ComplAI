package cat.complai.openrouter.helpers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QueryPreprocessor Tests")
class QueryPreprocessorTest {

    @Test
    @DisplayName("preprocess normalizes to lowercase")
    void test_preprocess_normalizesLowercase() {
        String result = QueryPreprocessor.preprocess("QUAN TANCA?");
        assertTrue(result.contains("quan"));
        assertTrue(result.contains("tanca"));
    }

    @Test
    @DisplayName("preprocess collapses multiple spaces to single space")
    void test_preprocess_collapseWhitespace() {
        String result = QueryPreprocessor.preprocess("quan    tanca    la    biblioteca?");
        assertEquals("quan tanca la biblioteca", result.trim());
    }

    @Test
    @DisplayName("preprocess removes accents")
    void test_preprocess_removesAccents() {
        // Catalan accents
        String result1 = QueryPreprocessor.preprocess("Quan tanca L'Ajuntament?");
        assertTrue(result1.contains("quan"));
        assertTrue(result1.contains("tanca"));

        // Multiple accent types
        String result2 = QueryPreprocessor.preprocess("café résumé naïve");
        assertTrue(result2.contains("cafe"));
        assertTrue(result2.contains("resume"));
        assertTrue(result2.contains("naive"));
    }

    @Test
    @DisplayName("preprocess removes accents: é→e")
    void test_preprocess_removesAccent_acute_e() {
        String result = QueryPreprocessor.preprocess("électrique");
        assertEquals("electrique", result);
    }

    @Test
    @DisplayName("preprocess removes accents: à→a")
    void test_preprocess_removesAccent_grave_a() {
        String result = QueryPreprocessor.preprocess("à bientôt");
        assertTrue(result.contains("a"));
        assertTrue(result.contains("bientot"));
    }

    @Test
    @DisplayName("preprocess removes accents: ç→c")
    void test_preprocess_removesAccent_cedilla() {
        String result = QueryPreprocessor.preprocess("façade français");
        assertTrue(result.contains("facade"));
        assertTrue(result.contains("francais"));
    }

    @Test
    @DisplayName("preprocess handles null gracefully")
    void test_preprocess_handlesNull() {
        String result = QueryPreprocessor.preprocess(null);
        assertEquals("", result);
    }

    @Test
    @DisplayName("preprocess handles blank strings")
    void test_preprocess_handlesBlank() {
        assertEquals("", QueryPreprocessor.preprocess(""));
        assertEquals("", QueryPreprocessor.preprocess("   "));
        assertEquals("", QueryPreprocessor.preprocess("\t\n"));
    }

    @Test
    @DisplayName("preprocess trims leading/trailing whitespace")
    void test_preprocess_trimsWhitespace() {
        String result = QueryPreprocessor.preprocess("  quan tanca  ");
        assertEquals("quan tanca", result);
    }

    @Test
    @DisplayName("removeStopWords filters common English stop words")
    void test_removeStopWords_filtersEnglish() {
        String result = QueryPreprocessor.removeStopWords("how to recycle paper");
        // "how", "to" are stop words; "recycle", "paper" are not
        assertTrue(result.contains("recycle"));
        assertTrue(result.contains("paper"));
        assertFalse(result.contains(" to "));
    }

    @Test
    @DisplayName("removeStopWords filters common Catalan stop words")
    void test_removeStopWords_filtersCatalan() {
        String result = QueryPreprocessor.removeStopWords("quan tanca la biblioteca");
        // "quan", "la" are stop words; "tanca", "biblioteca" are not
        assertTrue(result.contains("tanca"));
        assertTrue(result.contains("biblioteca"));
    }

    @Test
    @DisplayName("removeStopWords filters common Spanish stop words")
    void test_removeStopWords_filtersSpanish() {
        String result = QueryPreprocessor.removeStopWords("como reciclar el papel");
        // "como", "el" are stop words; "reciclar", "papel" are not
        assertTrue(result.contains("reciclar"));
        assertTrue(result.contains("papel"));
    }

    @Test
    @DisplayName("removeStopWords fallback to original if all words are stop words")
    void test_removeStopWords_fallbackToOriginal() {
        String result = QueryPreprocessor.removeStopWords("a the and or");
        // All words are stop words; should return original
        assertEquals("a the and or", result);
    }

    @Test
    @DisplayName("removeStopWords preserves queries with no stop words")
    void test_removeStopWords_preservesNonStops() {
        String query = "procedure recycling application";
        String result = QueryPreprocessor.removeStopWords(query);
        assertEquals(query, result);
    }

    @Test
    @DisplayName("removeStopWords handles null gracefully")
    void test_removeStopWords_handlesNull() {
        String result = QueryPreprocessor.removeStopWords(null);
        assertNull(result);
    }

    @Test
    @DisplayName("removeStopWords handles blank strings")
    void test_removeStopWords_handlesBlank() {
        // For blank input, removeStopWords returns the input as-is
        String result1 = QueryPreprocessor.removeStopWords("");
        assertTrue(result1.isEmpty());
    }

    @Test
    @DisplayName("full pipeline: preprocess then remove stop words")
    void test_pipeline_preprocessThenRemoveStops() {
        String original = "Quan tanca    LA   biblioteca?";
        String cleaned = QueryPreprocessor.preprocess(original);
        String final_result = QueryPreprocessor.removeStopWords(cleaned);

        // Should handle accents, whitespace, and stop words
        assertTrue(final_result.contains("tanca"));
        assertTrue(final_result.contains("biblioteca"));
    }

    @Test
    @DisplayName("preprocess handles special characters")
    void test_preprocess_handlesSpecialCharacters() {
        String result = QueryPreprocessor.preprocess("café-restaurant @ 12:30");
        assertTrue(result.contains("restaurant"));
        assertTrue(result.contains("cafe"));
    }

    @Test
    @DisplayName("preprocess handles numbers correctly")
    void test_preprocess_preservesNumbers() {
        String result = QueryPreprocessor.preprocess("apartment 123 district 4");
        assertTrue(result.contains("123"));
        assertTrue(result.contains("4"));
    }
}
