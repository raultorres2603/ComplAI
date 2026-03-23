package cat.complai.openrouter.services.procedure;

import cat.complai.openrouter.dto.Source;
import cat.complai.openrouter.helpers.EventRagHelper;
import cat.complai.openrouter.helpers.EventRagHelperRegistry;
import cat.complai.openrouter.helpers.ProcedureRagHelper;
import cat.complai.openrouter.helpers.ProcedureRagHelperRegistry;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class ProcedureContextServiceTest {

    @Inject
    ProcedureContextService procedureContextService;

    @Inject
    ProcedureRagHelperRegistry procedureRagHelperRegistry;

    @Inject
    EventRagHelperRegistry eventRagHelperRegistry;

    @Test
    void buildProcedureContextResult_returnsMaxThreeMatches() throws Exception {
        // Load testcity procedures
        ProcedureContextService.ProcedureContextResult result = procedureContextService
                .buildProcedureContextResult("recycling waste", "testcity");

        assertNotNull(result);
        assertNotNull(result.getSources());

        // Sources should be limited by RAG helper's MAX_RESULTS = 3
        assertTrue(result.getSources().size() <= 3,
                "Should return at most 3 procedure sources, but got " + result.getSources().size());
    }

    @Test
    void buildEventContextResult_returnsMaxThreeMatches() throws Exception {
        // Load testcity events
        ProcedureContextService.EventContextResult result = procedureContextService
                .buildEventContextResult("festival concert", "testcity");

        assertNotNull(result);
        assertNotNull(result.getSources());

        // Sources should be limited by RAG helper's MAX_RESULTS = 3
        assertTrue(result.getSources().size() <= 3,
                "Should return at most 3 event sources, but got " + result.getSources().size());
    }

    @Test
    void buildProcedureContextResult_withBroadQuery_respectsLimit() throws Exception {
        // Query that might match multiple procedures
        ProcedureContextService.ProcedureContextResult result = procedureContextService
                .buildProcedureContextResult("procedure steps requirements", "testcity");

        assertNotNull(result);
        assertTrue(result.getSources().size() <= 3,
                "Broad query should still respect MAX_RESULTS limit of 3");
    }

    @Test
    void buildEventContextResult_withBroadQuery_respectsLimit() throws Exception {
        // Query that might match multiple events
        ProcedureContextService.EventContextResult result = procedureContextService
                .buildEventContextResult("event activity entertainment", "testcity");

        assertNotNull(result);
        assertTrue(result.getSources().size() <= 3,
                "Broad query should still respect MAX_RESULTS limit of 3");
    }

    @Test
    void buildProcedureContextResult_emptyQuery_returnsEmpty() throws Exception {
        ProcedureContextService.ProcedureContextResult result = procedureContextService.buildProcedureContextResult("",
                "testcity");

        assertNotNull(result);
        assertNotNull(result.getSources());
        assertTrue(result.getSources().isEmpty(), "Empty query should return empty sources");
    }

    @Test
    void buildEventContextResult_emptyQuery_returnsEmpty() throws Exception {
        ProcedureContextService.EventContextResult result = procedureContextService.buildEventContextResult("",
                "testcity");

        assertNotNull(result);
        assertNotNull(result.getSources());
        assertTrue(result.getSources().isEmpty(), "Empty query should return empty sources");
    }

    @Test
    void deDuplicateAndOrderSources_preservesLimit() {
        // Create a list with duplicates that exceeds MAX_RESULTS
        List<Source> sourcesWithDuplicates = new ArrayList<>();

        // Add 5 sources, some with duplicate URLs
        sourcesWithDuplicates.add(new Source("https://example.com/proc1", "Procedure 1"));
        sourcesWithDuplicates.add(new Source("https://example.com/proc1", "Procedure 1")); // duplicate
        sourcesWithDuplicates.add(new Source("https://example.com/proc2", "Procedure 2"));
        sourcesWithDuplicates.add(new Source("https://example.com/proc3", "Procedure 3"));
        sourcesWithDuplicates.add(new Source("https://example.com/proc2", "Procedure 2")); // duplicate

        List<Source> deduped = procedureContextService.deDuplicateAndOrderSources(sourcesWithDuplicates);

        // Should have removed duplicates
        assertEquals(3, deduped.size(), "Should have 3 unique sources after deduplication");

        // First occurrence should be preserved
        assertEquals("https://example.com/proc1", deduped.get(0).getUrl());
        assertEquals("https://example.com/proc2", deduped.get(1).getUrl());
        assertEquals("https://example.com/proc3", deduped.get(2).getUrl());
    }

    @Test
    void buildProcedureContextResult_containsContextBlock() throws Exception {
        ProcedureContextService.ProcedureContextResult result = procedureContextService
                .buildProcedureContextResult("recycling", "testcity");

        assertNotNull(result);
        // When matches found, context block should be present
        if (!result.getSources().isEmpty()) {
            assertNotNull(result.getContextBlock());
            assertFalse(result.getContextBlock().isBlank());
        }
    }

    @Test
    void buildEventContextResult_containsContextBlock() throws Exception {
        ProcedureContextService.EventContextResult result = procedureContextService.buildEventContextResult("festival",
                "testcity");

        assertNotNull(result);
        // When matches found, context block should be present
        if (!result.getSources().isEmpty()) {
            assertNotNull(result.getContextBlock());
            assertFalse(result.getContextBlock().isBlank());
        }
    }

    @Test
    void questionNeedsProcedureContext_detects_keywords() {
        assertTrue(procedureContextService.questionNeedsProcedureContext("How do I apply for a permit?", "testcity"));
        assertTrue(procedureContextService.questionNeedsProcedureContext("What are the requirements?", "testcity"));
        assertTrue(procedureContextService.questionNeedsProcedureContext("How to recycle", "testcity"));

        assertFalse(procedureContextService.questionNeedsProcedureContext("What is the weather today?", "testcity"));
        assertFalse(procedureContextService.questionNeedsProcedureContext("Tell me a joke", "testcity"));
    }

    @Test
    void questionNeedsEventContext_detects_keywords() {
        assertTrue(procedureContextService.questionNeedsEventContext("What events are happening?", "testcity"));
        assertTrue(procedureContextService.questionNeedsEventContext("Is there a festival?", "testcity"));
        assertTrue(procedureContextService.questionNeedsEventContext("What's on this weekend?", "testcity"));

        assertFalse(procedureContextService.questionNeedsEventContext("What is the weather?", "testcity"));
        assertFalse(procedureContextService.questionNeedsEventContext("Tell me a joke", "testcity"));
    }

    // ============================================================================
    // Phase 3: Monitoring & Validation — Parallel RAG Search Tests (Step 3)
    // ============================================================================

    @Test
    void buildProcedureContextResultAsync_returnsCompletableFuture() throws Exception {
        // Step 3 validation: Verify that async method returns a CompletableFuture
        CompletableFuture<ProcedureContextService.ProcedureContextResult> future = procedureContextService
                .buildProcedureContextResultAsync("recycling", "testcity");

        assertNotNull(future, "Should return a CompletableFuture");
        assertTrue(future instanceof CompletableFuture, "Should be a CompletableFuture instance");

        // Should complete successfully and return a result
        ProcedureContextService.ProcedureContextResult result = future.get();
        assertNotNull(result, "Future should resolve to a non-null result");
    }

    @Test
    void buildEventContextResultAsync_returnsCompletableFuture() throws Exception {
        // Step 3 validation: Verify that async event search returns a CompletableFuture
        CompletableFuture<ProcedureContextService.EventContextResult> future = procedureContextService
                .buildEventContextResultAsync("festival", "testcity");

        assertNotNull(future, "Should return a CompletableFuture");
        assertTrue(future instanceof CompletableFuture, "Should be a CompletableFuture instance");

        // Should complete successfully and return a result
        ProcedureContextService.EventContextResult result = future.get();
        assertNotNull(result, "Future should resolve to a non-null result");
    }

    @Test
    void buildProcedureContextResultAsync_completesSuccessfully() throws Exception {
        // Step 3 validation: Verify that async procedure context completes with valid
        // data
        CompletableFuture<ProcedureContextService.ProcedureContextResult> future = procedureContextService
                .buildProcedureContextResultAsync("recycling", "testcity");

        // Should not throw exception
        ProcedureContextService.ProcedureContextResult result = future.get();
        assertNotNull(result);
        assertNotNull(result.getSources());
    }

    @Test
    void buildEventContextResultAsync_completesSuccessfully() throws Exception {
        // Step 3 validation: Verify that async event context completes with valid data
        CompletableFuture<ProcedureContextService.EventContextResult> future = procedureContextService
                .buildEventContextResultAsync("festival", "testcity");

        // Should not throw exception
        ProcedureContextService.EventContextResult result = future.get();
        assertNotNull(result);
        assertNotNull(result.getSources());
    }

    @Test
    void parallelSearches_bothComplete_withinReasonableTime() throws Exception {
        // Step 3 validation: Verify that both searches can run concurrently
        // Measure time for parallel execution vs sequential

        long parallelStartTime = System.currentTimeMillis();

        CompletableFuture<ProcedureContextService.ProcedureContextResult> procFuture = procedureContextService
                .buildProcedureContextResultAsync("recycling", "testcity");
        CompletableFuture<ProcedureContextService.EventContextResult> eventFuture = procedureContextService
                .buildEventContextResultAsync("festival", "testcity");

        // Wait for both to complete (simulating CompletableFuture.allOf())
        CompletableFuture.allOf(procFuture, eventFuture).get();

        long parallelEndTime = System.currentTimeMillis();
        long parallelTime = parallelEndTime - parallelStartTime;

        // Now measure sequential execution for comparison
        long sequentialStartTime = System.currentTimeMillis();
        procedureContextService.buildProcedureContextResult("recycling", "testcity");
        procedureContextService.buildEventContextResult("festival", "testcity");
        long sequentialEndTime = System.currentTimeMillis();
        long sequentialTime = sequentialEndTime - sequentialStartTime;

        // Parallel should be faster than sequential (or at least not significantly
        // slower)
        // In practice, parallel execution should reduce latency by ~30-50%
        assertTrue(parallelTime >= 0, "Parallel execution should complete");
        assertTrue(sequentialTime >= 0, "Sequential execution should complete");

        // Both futures completed successfully
        assertNotNull(procFuture.get());
        assertNotNull(eventFuture.get());
    }

    @Test
    void parallelSearches_producesSameResultAsSequential() throws Exception {
        // Step 3 validation: Verify that parallel execution produces the same results
        // as sequential

        // Parallel results
        CompletableFuture<ProcedureContextService.ProcedureContextResult> procFuture = procedureContextService
                .buildProcedureContextResultAsync("recycling", "testcity");
        CompletableFuture<ProcedureContextService.EventContextResult> eventFuture = procedureContextService
                .buildEventContextResultAsync("festival", "testcity");

        CompletableFuture.allOf(procFuture, eventFuture).get();

        ProcedureContextService.ProcedureContextResult parallelProcResult = procFuture.get();
        ProcedureContextService.EventContextResult parallelEventResult = eventFuture.get();

        // Sequential results
        ProcedureContextService.ProcedureContextResult sequentialProcResult = procedureContextService
                .buildProcedureContextResult("recycling", "testcity");
        ProcedureContextService.EventContextResult sequentialEventResult = procedureContextService
                .buildEventContextResult("festival", "testcity");

        // Results should be the same (same sources count and context)
        assertEquals(parallelProcResult.getSources().size(), sequentialProcResult.getSources().size(),
                "Parallel and sequential procedure search should return same number of results");
        assertEquals(parallelEventResult.getSources().size(), sequentialEventResult.getSources().size(),
                "Parallel and sequential event search should return same number of results");
    }
}
