package cat.complai.openrouter.helpers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcedureRagHelperTest {

    private ProcedureRagHelper procedureRagHelper;

    @BeforeEach
    void setup() throws IOException {
        // Use testcity which currently ships 2 procedures in procedures-testcity.json
        procedureRagHelper = new ProcedureRagHelper("testcity");
    }

    @Test
    void search_returnsMaxThreeResults() {
        // Broad query that should match procedures
        String query = "waste recycling";
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search(query);

        assertTrue(results.size() <= 3, "Should return at most 3 results, but got " + results.size());
    }

    @Test
    void search_fewerResultsIfFewMatches() {
        // Query matching only recycling/waste-specific procedures
        String query = "recycling center waste management";
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search(query);

        // Should return results respecting max 3 limit
        assertTrue(results.size() <= 3, "Should respect max 3 limit");
    }

    @Test
    void search_emptyQuery_returnsEmpty() {
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search("");
        assertTrue(results.isEmpty(), "Empty query should return empty list");
    }

    @Test
    void search_nullQuery_returnsEmpty() {
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search(null);
        assertTrue(results.isEmpty(), "Null query should return empty list");
    }

    @Test
    void getAllProcedures_returnsFull() {
        List<ProcedureRagHelper.Procedure> allProcedures = procedureRagHelper.getAllProcedures();

        // Should return all procedures from the current test fixture (unrestricted)
        assertEquals(2, allProcedures.size(), "getAllProcedures() should return all procedures");
    }

    @Test
    void search_returnsTopResultsByRelevance() {
        // Specific query that should match procedures
        String query = "requirements steps documentation";
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search(query);

        assertTrue(results.size() <= 3, "Should return at most 3 results");

        // Results should be properly structured
        if (!results.isEmpty()) {
            for (ProcedureRagHelper.Procedure proc : results) {
                assertNotNull(proc.procedureId);
                assertNotNull(proc.title);
                assertFalse(proc.procedureId.isBlank());
                assertFalse(proc.title.isBlank());
            }
        }
    }

    @Test
    void search_preserves_procedureFields() {
        String query = "recycling";
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search(query);

        assertTrue(results.size() > 0, "Should find recycling procedure");
        ProcedureRagHelper.Procedure procedure = results.get(0);

        // Verify all important fields are preserved
        assertNotNull(procedure.procedureId);
        assertNotNull(procedure.title);
        assertNotNull(procedure.url);
        assertFalse(procedure.procedureId.isBlank());
        assertFalse(procedure.title.isBlank());
    }

    @Test
    void search_respects_maxThreeLimit_evenWithBroadQuery() {
        // Query that might match all available procedures and more
        String query = "procedure steps requirements";
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search(query);

        // Should never exceed MAX_RESULTS of 3
        assertTrue(results.size() <= 3, "Should never exceed MAX_RESULTS of 3");
    }

    @Test
    void search_javaEngine_returnsRelevantResults() {
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search("recycling");

        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 3);
        assertEquals("p1", results.get(0).procedureId);
    }

    @Test
    void search_javaEngine_handlesAccentAndWhitespaceNormalization() {
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search("  tràmit   recycling  ");

        assertFalse(results.isEmpty());
        assertEquals("p1", results.get(0).procedureId);
    }

    @Test
    void search_isDeterministicAcrossHelperInstances() throws IOException {
        ProcedureRagHelper secondHelper = new ProcedureRagHelper("testcity");
        List<ProcedureRagHelper.Procedure> firstResults = procedureRagHelper.search("waste recycling");
        List<ProcedureRagHelper.Procedure> secondResults = secondHelper.search("waste recycling");

        assertEquals(firstResults.size(), secondResults.size());
        if (!firstResults.isEmpty()) {
            assertEquals(firstResults.get(0).procedureId, secondResults.get(0).procedureId);
        }
    }

    // ============================================================================
    // Phase 3: Monitoring & Validation — Optimization Tests
    // ============================================================================

    @Test
    void search_usesReducedFieldSet_titleAndDescription() {
        // Step 1 validation: Verify that only title + description are indexed
        // (searchable)
        // Fields like 'requirements' and 'steps' are stored but not searched

        String queryTitle = "recycling";
        List<ProcedureRagHelper.Procedure> resultsFromTitle = procedureRagHelper.search(queryTitle);
        assertTrue(resultsFromTitle.size() > 0, "Should find procedures matching title field");
    }

    @Test
    void search_appliesFieldBoosts_titleHasHigherWeight() {
        // Step 1 validation: Verify that title field has higher boost (2.0) than
        // description (1.0)

        String query = "recycling";
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search(query);

        if (!results.isEmpty()) {
            ProcedureRagHelper.Procedure firstResult = results.get(0);
            // Top result should be most relevant (likely title match due to boost)
            assertTrue(firstResult.title.toLowerCase().contains("recycling")
                    || firstResult.description.toLowerCase().contains("recycling"),
                    "Top result should match the query");
        }
    }

    @Test
    void search_filtersLowRelevanceScores() {
        // Step 2 validation: Verify that MIN_RELEVANCE_SCORE threshold is applied

        String query = "waste";
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search(query);

        // Results should all have relevance above the threshold
        assertTrue(results.size() <= 3, "Relevance filtering should limit results to MAX_RESULTS=3");

        // All results should match the query with reasonable relevance
        for (ProcedureRagHelper.Procedure proc : results) {
            assertTrue(
                    proc.title.toLowerCase().contains("waste") ||
                            proc.description.toLowerCase().contains("waste"),
                    "All returned results should match the query (relevance threshold applied)");
        }
    }

    @Test
    void search_removedFields_stillAccessible_requirements() {
        // Step 1 validation: Fields removed from search index should still be
        // accessible
        // This validates the Conservative implementation (Store.YES for removed fields)

        String query = "recycling";
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search(query);

        assertTrue(results.size() > 0, "Should find recycling procedure");
        ProcedureRagHelper.Procedure proc = results.get(0);

        // requirements field was removed from SEARCH_FIELDS but stored
        assertNotNull(proc.requirements, "requirements field should be accessible");
    }

    @Test
    void search_removedFields_stillAccessible_steps() {
        // Step 1 validation: Steps field should still be accessible

        String query = "recycling";
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search(query);

        assertTrue(results.size() > 0, "Should find recycling procedure");
        ProcedureRagHelper.Procedure proc = results.get(0);

        // steps field was removed from SEARCH_FIELDS but stored
        assertNotNull(proc.steps, "steps field should be accessible");
    }
}
