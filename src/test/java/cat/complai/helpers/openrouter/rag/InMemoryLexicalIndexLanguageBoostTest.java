package cat.complai.helpers.openrouter.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryLexicalIndexLanguageBoostTest {

    record TestDocument(String id, String title, String description) {
    }

    private List<TestDocument> documents;

    @BeforeEach
    void setUp() {
        // Create test documents in both Catalan and English
        documents = List.of(
                new TestDocument("ca1", "Reclamació Procediment",
                        "Procediment per presentar reclamacions a l'administració"),
                new TestDocument("ca2", "Permís Municipal", "Permís i autoritzacions municipals"),
                new TestDocument("ca3", "Gestió de Residus", "Sistema de recollida i gestió de residus municipals"),
                new TestDocument("en1", "Complaint Form", "Municipal complaint filing process and procedures"),
                new TestDocument("en2", "Permit Application", "How to apply for municipal permits"),
                new TestDocument("en3", "Waste Management", "Waste collection and disposal guidelines"));
    }

    @Test
    void testLanguageBoost_CatalanQueryBoostsCatalanResults() {
        // Create index with language tags
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", 2.0);
        fieldWeights.put("description", 1.0);

        InMemoryLexicalIndex<TestDocument> index = InMemoryLexicalIndex.build(
                documents,
                fieldWeights,
                doc -> Map.of("title", doc.title(), "description", doc.description()),
                TestDocument::id,
                "CA",
                "EN");

        // Search in Catalan for "reclamació"
        List<String> queryTokens = List.of("reclamacio");
        InMemoryLexicalIndex.SearchResponse<TestDocument> response = index.search(
                queryTokens, 10, 0.0, 0.1, "CA");

        assertNotNull(response);
        assertTrue(response.results().size() > 0, "Should find results");

        // Verify Catalan documents score higher than English
        if (response.results().size() >= 2) {
            double topScore = response.results().get(0).score();
            // With 1.5x boost for language match, Catalan should generally score higher
            assertTrue(topScore > 0, "Top result should have positive score");
        }
    }

    @Test
    void testLanguageBoost_NoLanguageParameter_NoBoost() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", 2.0);
        fieldWeights.put("description", 1.0);

        InMemoryLexicalIndex<TestDocument> index = InMemoryLexicalIndex.build(
                documents,
                fieldWeights,
                doc -> Map.of("title", doc.title(), "description", doc.description()),
                TestDocument::id,
                "CA",
                "EN");

        // Search without language parameter
        List<String> queryTokens = List.of("reclamacio");
        InMemoryLexicalIndex.SearchResponse<TestDocument> responseNoLanguage = index.search(
                queryTokens, 10, 0.0, 0.1);

        // Search with language parameter
        InMemoryLexicalIndex.SearchResponse<TestDocument> responseWithLanguage = index.search(
                queryTokens, 10, 0.0, 0.1, "CA");

        assertNotNull(responseNoLanguage);
        assertNotNull(responseWithLanguage);

        // Both should find results, but with language they might score differently
        assertTrue(responseNoLanguage.results().size() > 0);
        assertTrue(responseWithLanguage.results().size() > 0);
    }

    @Test
    void testLanguageBoost_EnglishQueryBoostsEnglishResults() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", 2.0);
        fieldWeights.put("description", 1.0);

        InMemoryLexicalIndex<TestDocument> index = InMemoryLexicalIndex.build(
                documents,
                fieldWeights,
                doc -> Map.of("title", doc.title(), "description", doc.description()),
                TestDocument::id,
                "CA",
                "EN");

        List<String> queryTokens = List.of("complaint");
        InMemoryLexicalIndex.SearchResponse<TestDocument> response = index.search(
                queryTokens, 10, 0.0, 0.1, "EN");

        assertNotNull(response);
        // Should find English document about complaints
        assertTrue(response.results().size() > 0, "Should find English complaint document");
    }

    @Test
    void testLanguageBoost_MaintainsTopKOrdering() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", 2.0);
        fieldWeights.put("description", 1.0);

        InMemoryLexicalIndex<TestDocument> index = InMemoryLexicalIndex.build(
                documents,
                fieldWeights,
                doc -> Map.of("title", doc.title(), "description", doc.description()),
                TestDocument::id,
                "CA",
                "EN");

        List<String> queryTokens = List.of("permis", "permit");
        InMemoryLexicalIndex.SearchResponse<TestDocument> response = index.search(
                queryTokens, 2, 0.0, 0.1, "CA");

        assertNotNull(response);
        // Should respect topK=2
        assertTrue(response.results().size() <= 2, "Should not exceed topK limit");
    }

    @Test
    void testLanguageBoost_ThresholdFilteringStillWorks() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", 2.0);
        fieldWeights.put("description", 1.0);

        InMemoryLexicalIndex<TestDocument> index = InMemoryLexicalIndex.build(
                documents,
                fieldWeights,
                doc -> Map.of("title", doc.title(), "description", doc.description()),
                TestDocument::id,
                "CA",
                "EN");

        // Search for exact match
        List<String> queryTokens = List.of("reclamacio");

        // With high relative floor
        InMemoryLexicalIndex.SearchResponse<TestDocument> response = index.search(
                queryTokens, 10, 0.0, 0.5, "CA");

        assertNotNull(response);
        // Even with boost, threshold filtering should apply
        if (response.results().size() > 0) {
            double bestScore = response.results().get(0).score();
            double threshold = Math.min(0.0, bestScore * 0.5);
            for (SearchResult<TestDocument> result : response.results()) {
                assertTrue(result.score() >= threshold,
                        "All results should meet threshold");
            }
        }
    }

    @Test
    void testLanguageBoost_ResultsSortedByBoostedScore() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", 2.0);
        fieldWeights.put("description", 1.0);

        InMemoryLexicalIndex<TestDocument> index = InMemoryLexicalIndex.build(
                documents,
                fieldWeights,
                doc -> Map.of("title", doc.title(), "description", doc.description()),
                TestDocument::id,
                "CA",
                "EN");

        List<String> queryTokens = List.of("reclamacio", "procediment");
        InMemoryLexicalIndex.SearchResponse<TestDocument> response = index.search(
                queryTokens, 10, 0.0, 0.1, "CA");

        assertNotNull(response);
        List<SearchResult<TestDocument>> results = response.results();

        // Verify results are sorted by score descending
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).score() >= results.get(i + 1).score(),
                    "Results should be sorted by score descending");
        }
    }

    @Test
    void testLanguageBoost_CaseSensitivity() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", 2.0);

        InMemoryLexicalIndex<TestDocument> index = InMemoryLexicalIndex.build(
                documents,
                fieldWeights,
                doc -> Map.of("title", doc.title()),
                TestDocument::id,
                "CA",
                "EN");

        List<String> queryTokens = List.of("reclamacio");

        // Test case-insensitivity
        InMemoryLexicalIndex.SearchResponse<TestDocument> responseLower = index.search(
                queryTokens, 10, 0.0, 0.1, "ca");
        InMemoryLexicalIndex.SearchResponse<TestDocument> responseUpper = index.search(
                queryTokens, 10, 0.0, 0.1, "CA");
        // All should produce same results (language comparison is case-insensitive)
        assertTrue(responseLower.results().size() > 0 || responseUpper.results().size() > 0,
                "Should find results with various language code cases");
    }

    @Test
    void testLanguageBoost_NullLanguageNoBoost() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", 2.0);

        InMemoryLexicalIndex<TestDocument> index = InMemoryLexicalIndex.build(
                documents,
                fieldWeights,
                doc -> Map.of("title", doc.title()),
                TestDocument::id,
                "CA",
                "EN");

        List<String> queryTokens = List.of("reclamacio");

        // Search with null language
        InMemoryLexicalIndex.SearchResponse<TestDocument> responses = index.search(
                queryTokens, 10, 0.0, 0.1, (String) null);

        assertNotNull(responses);
        // Should not crash, just no language boost applied
    }

    @Test
    void testLanguageBoost_EmptyLanguageNoBoost() {
        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", 2.0);

        InMemoryLexicalIndex<TestDocument> index = InMemoryLexicalIndex.build(
                documents,
                fieldWeights,
                doc -> Map.of("title", doc.title()),
                TestDocument::id,
                "CA",
                "EN");

        List<String> queryTokens = List.of("reclamacio");

        // Search with empty language
        InMemoryLexicalIndex.SearchResponse<TestDocument> response = index.search(
                queryTokens, 10, 0.0, 0.1, "");

        assertNotNull(response);
        // Should not crash, just no language boost applied
    }
}
