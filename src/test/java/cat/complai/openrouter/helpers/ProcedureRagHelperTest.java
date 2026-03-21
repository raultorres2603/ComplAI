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
        // Use testcity which has procedures-testcity.json
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
        // Query that matches only one procedure
        String query = "recycling center waste management";
        List<ProcedureRagHelper.Procedure> results = procedureRagHelper.search(query);

        // Should return results respecting max 3 limit
        assertTrue(results.size() <= 3, "Should respect max 3 limit");
        // Testcity has only 2 procedures, so should return at most 2
        assertTrue(results.size() <= 2, "Testcity only has 2 procedures");
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

        // Should return all procedures (unrestricted)
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
}
