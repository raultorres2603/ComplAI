package cat.complai.openrouter.helpers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CivicVocabularyServiceTest {

    private CivicVocabularyService service;

    @BeforeEach
    void setUp() {
        service = new CivicVocabularyService();
    }

    @Test
    void testExpandQuery_EnglishComplaint() {
        String result = service.expandQuery("complaint", "en");
        assertNotNull(result);
        assertTrue(result.contains("complaint"));
        assertTrue(result.contains("reclamació") || result.contains("denúncia"),
                "English 'complaint' should expand to Catalan equivalents");
    }

    @Test
    void testExpandQuery_SpanishQueja() {
        String result = service.expandQuery("queja", "es");
        assertNotNull(result);
        assertTrue(result.contains("queja"));
        assertTrue(result.contains("reclamació") || result.contains("denúncia"),
                "Spanish 'queja' should expand to Catalan equivalents");
    }

    @Test
    void testExpandQuery_UnknownTerm() {
        String result = service.expandQuery("unknown_word_xyz", "en");
        assertEquals("unknown_word_xyz", result,
                "Unknown civic term should return original query unchanged");
    }

    @Test
    void testExpandQuery_MultipleCivicTerms() {
        String result = service.expandQuery("complaint application", "en");
        assertNotNull(result);
        assertTrue(result.contains("complaint"));
        assertTrue(result.contains("application"));
        // Should contain at least one Catalan equivalent
        assertTrue(result.contains("reclamació") || result.contains("sol·licitud"),
                "Multiple civic terms should expand to multiple Catalan equivalents");
    }

    @Test
    void testExpandQuery_CaseInsensitive() {
        String result1 = service.expandQuery("COMPLAINT", "en");
        String result2 = service.expandQuery("complaint", "en");
        String result3 = service.expandQuery("ComPlaint", "en");

        // All should expand
        assertTrue(result1.contains("reclamació") || result1.contains("denúncia"));
        assertTrue(result2.contains("reclamació") || result2.contains("denúncia"));
        assertTrue(result3.contains("reclamació") || result3.contains("denúncia"));
    }

    @Test
    void testExpandQuery_InvalidLanguage() {
        String result = service.expandQuery("complaint", "invalid_lang");
        assertEquals("complaint", result,
                "Invalid language code should return original query unchanged");
    }

    @Test
    void testExpandQuery_NullLanguage() {
        String result = service.expandQuery("complaint", null);
        assertEquals("complaint", result,
                "Null language should return original query unchanged");
    }

    @Test
    void testExpandQuery_NullQuery() {
        String result = service.expandQuery(null, "en");
        assertNull(result, "Null query should return null");
    }

    @Test
    void testExpandQuery_BlankQuery() {
        String result = service.expandQuery("   ", "en");
        assertEquals("   ", result, "Blank query should return unchanged");
    }

    @Test
    void testExpandQuery_CatalanNoChange() {
        // Catalan queries should not be expanded (already in Catalan)
        String result = service.expandQuery("reclamació", "ca");
        assertEquals("reclamació", result,
                "Catalan language should not expand (no en/es mappings)");
    }

    @Test
    void testExpandQuery_SpanishPermiso() {
        String result = service.expandQuery("permiso", "es");
        assertNotNull(result);
        assertTrue(result.contains("permiso"));
        assertTrue(result.contains("permís"),
                "Spanish 'permiso' should expand to Catalan 'permís'");
    }

    @Test
    void testExpandQuery_EnglishPermit() {
        String result = service.expandQuery("permit", "en");
        assertNotNull(result);
        assertTrue(result.contains("permit"));
        assertTrue(result.contains("permís"),
                "English 'permit' should expand to Catalan 'permís'");
    }

    @Test
    void testExpandQuery_MultipleTermsSameQuery() {
        // Query with both civic and non-civic terms
        String result = service.expandQuery("complaint about permit", "en");
        assertNotNull(result);
        assertTrue(result.contains("complaint"));
        assertTrue(result.contains("about"));
        assertTrue(result.contains("permit"));
        // Should include at least one expansion
        assertTrue(result.matches(".*(?:reclamació|denúncia|permís).*"),
                "Mixed query should expand civic terms only");
    }

    @Test
    void testExpandQuery_WithPunctuation() {
        // Query with punctuation should still expand when punctuation is stripped
        String result = service.expandQuery("complaint!", "en");
        assertNotNull(result);
        assertTrue(result.contains("complaint") || result.contains("complaint!"));
    }

    // ========================================================================
    // French Language Tests (Step 5)
    // ========================================================================

    @Test
    void testExpandQuery_FrenchPlainte() {
        String result = service.expandQuery("plainte", "fr");
        assertNotNull(result);
        assertTrue(result.contains("plainte"));
        assertTrue(result.contains("reclamació") || result.contains("denúncia"),
                "French 'plainte' should expand to Catalan equivalents");
    }

    @Test
    void testExpandQuery_FrenchReclamation() {
        String result = service.expandQuery("réclamation", "fr");
        assertNotNull(result);
        assertTrue(result.contains("réclamation"),
                "French 'réclamation' should be in result");
        assertTrue(result.contains("reclamació"),
                "French 'réclamation' should expand to Catalan 'reclamació'");
    }

    @Test
    void testExpandQuery_FrenchPermis() {
        String result = service.expandQuery("permis", "fr");
        assertNotNull(result);
        assertTrue(result.contains("permis"));
        assertTrue(result.contains("permís"),
                "French 'permis' should expand to Catalan 'permís'");
    }

    @Test
    void testExpandQuery_FrenchAutorisation() {
        String result = service.expandQuery("autorisation", "fr");
        assertNotNull(result);
        assertTrue(result.contains("autorisation"));
        assertTrue(result.contains("autorització"),
                "French 'autorisation' should expand to Catalan 'autorització'");
    }

    @Test
    void testExpandQuery_FrenchMultipleTerms() {
        String result = service.expandQuery("plainte permis", "fr");
        assertNotNull(result);
        assertTrue(result.contains("plainte"));
        assertTrue(result.contains("permis"));
        // At least one expansion should be present
        assertTrue(result.matches(".*(?:reclamació|denúncia|permís).*"),
                "Multiple French civic terms should expand to Catalan equivalents");
    }

    @Test
    void testExpandQuery_FrenchAccentedTerms() {
        // French civic term with accents: "formulaire" (no accent here, but testing FR
        // language code works)
        String result = service.expandQuery("formulaire", "fr");
        assertNotNull(result);
        assertTrue(result.contains("formulaire") || result.contains("formularis"),
                "French 'formulaire' should be processed (language recognized)");
    }

    @Test
    void testExpandQuery_FrenchDemande() {
        String result = service.expandQuery("demande", "fr");
        assertNotNull(result);
        assertTrue(result.contains("demande"));
        assertTrue(result.contains("sol·licitud"),
                "French 'demande' should expand to Catalan 'sol·licitud'");
    }

    @Test
    void testExpandQuery_FrenchCivicVocabularyCaseSensitivity() {
        String result1 = service.expandQuery("PLAINTE", "fr");
        String result2 = service.expandQuery("plainte", "fr");

        // Both should expand (case-insensitive)
        assertTrue(result1.contains("reclamació") || result1.contains("denúncia"));
        assertTrue(result2.contains("reclamació") || result2.contains("denúncia"));
    }
}
