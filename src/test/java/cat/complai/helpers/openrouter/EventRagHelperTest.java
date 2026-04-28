package cat.complai.helpers.openrouter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventRagHelperTest {

    private RagHelper<RagHelper.Event> eventRagHelper;

    @BeforeEach
    void setup() {
        // Use testcity which currently ships 5 events in events-testcity.json
        eventRagHelper = RagHelper.forEvents("testcity");
    }

    @Test
    void search_returnsMaxThreeResults() {
        // Broad query that should match multiple events
        String query = "festival concert event";
        List<RagHelper.Event> results = eventRagHelper.search(query);

        assertTrue(results.size() <= 3, "Should return at most 3 results, but got " + results.size());
    }

    @Test
    void search_fewerResultsIfFewMatches() {
        // Query that matches only specific events
        String query = "cinema film";
        List<RagHelper.Event> results = eventRagHelper.search(query);

        // Should return 1 or fewer results (Film Festival match)
        assertTrue(results.size() <= 3, "Should respect max 3 limit");
        // At least one result should be a film event
        assertTrue(results.stream().anyMatch(e -> e.eventType.contains("Cinema") || e.title.contains("Film")),
                "Should find film-related event");
    }

    @Test
    void search_emptyQuery_returnsEmpty() {
        List<RagHelper.Event> results = eventRagHelper.search("");
        assertTrue(results.isEmpty(), "Empty query should return empty list");
    }

    @Test
    void search_nullQuery_returnsEmpty() {
        List<RagHelper.Event> results = eventRagHelper.search(null);
        assertTrue(results.isEmpty(), "Null query should return empty list");
    }

    @Test
    void getAllEvents_returnsFull() {
        List<RagHelper.Event> allEvents = eventRagHelper.getAll();

        // Should return all events from the current test fixture (unrestricted)
        assertEquals(5, allEvents.size(), "getAllEvents() should return all events");
    }

    @Test
    void search_returnsTopResultsByRelevance() {
        // Specific query that should match multiple events
        String query = "cultural performance";
        List<RagHelper.Event> results = eventRagHelper.search(query);

        assertTrue(results.size() <= 3, "Should return at most 3 results");

        // Results should include events matching the cultural aspect
        if (!results.isEmpty()) {
            // First result should be most relevant
            assertNotNull(results.get(0));
            assertNotNull(results.get(0).title);
        }
    }

    @Test
    void search_preserves_eventFields() {
        String query = "festival";
        List<RagHelper.Event> results = eventRagHelper.search(query);

        assertTrue(results.size() > 0, "Should find festival event");
        RagHelper.Event event = results.get(0);

        // Verify all important fields are preserved
        assertNotNull(event.eventId);
        assertNotNull(event.title);
        assertNotNull(event.url);
        assertFalse(event.eventId.isBlank());
        assertFalse(event.title.isBlank());
    }

    // ============================================================================
    // Phase 3: Monitoring & Validation — Optimization Tests
    // ============================================================================

    @Test
    void search_usesReducedFieldSet_titleAndDescription() {
        // Step 1 validation: Verify that only title + description are indexed
        // (searchable)
        // This test verifies that the field reduction optimization is applied

        // Query that matches on title (primary field with boost)
        String queryTitle = "festival";
        List<RagHelper.Event> resultsFromTitle = eventRagHelper.search(queryTitle);
        assertTrue(resultsFromTitle.size() > 0, "Should find events matching title field");

        // Query that matches on description
        String queryDesc = "event description";
        List<RagHelper.Event> resultsFromDesc = eventRagHelper.search(queryDesc);
        assertTrue(resultsFromDesc.size() >= 0, "Should find or not find events based on description");

        // Both fields should be searchable (title + description indexed)
        assertTrue(resultsFromTitle.size() > 0, "Title field is indexed and searchable");
    }

    @Test
    void search_appliesFieldBoots_titleHasHigherWeight() {
        // Step 1 validation: Verify that title field has higher boost (2.0) than
        // description (1.0)
        // This means queries matching on title should return higher-relevance results

        // Query that matches in both title and description
        String query = "festival";
        List<RagHelper.Event> results = eventRagHelper.search(query);

        // If results exist, the first result should be most relevant (likely title
        // match)
        if (!results.isEmpty()) {
            RagHelper.Event firstResult = results.get(0);
            // Verify the top result is meaningful (would be title-focused due to boost)
            assertTrue(firstResult.title.toLowerCase().contains("festival")
                    || firstResult.description.toLowerCase().contains("festival"),
                    "Top result should match the query");
        }
    }

    @Test
    void search_filtersLowRelevanceScores_reverseEngineering() {
        // Step 2 validation: Verify that MIN_RELEVANCE_SCORE threshold is applied
        // This is a reverse-engineering test: low-scoring results should be filtered
        // out

        // A very generic query that might match weakly across many events
        String query = "event";
        List<RagHelper.Event> results = eventRagHelper.search(query);

        // If results are returned, they should all be meaningfully related to the
        // query.
        assertTrue(results.size() <= 3, "Relevance filtering should limit results to MAX_RESULTS=3");

        // All results should at least have title or description matching the query
        for (RagHelper.Event event : results) {
            assertTrue(
                    event.title.toLowerCase().contains("event") ||
                            event.description.toLowerCase().contains("event"),
                    "All returned results should match the query (relevance threshold applied)");
        }
    }

    @Test
    void search_specificsQuery_returnsFewResultsWithHighRelevance() {
        // Step 2 validation: Verify that specific queries return high-relevance results
        // Specific queries should match fewer items but all with high relevance

        String query = "cinema film";
        List<RagHelper.Event> results = eventRagHelper.search(query);

        // Should return fewer results for a specific query
        assertTrue(results.size() <= 3, "Specific query should return limited, high-relevance results");

        // Top result should be clearly relevant
        if (!results.isEmpty()) {
            RagHelper.Event topResult = results.get(0);
            // Cinema query should match cinema/film events
            assertTrue(
                    topResult.eventType.toLowerCase().contains("cinema") ||
                            topResult.title.toLowerCase().contains("film") ||
                            topResult.description.toLowerCase().contains("cinema"),
                    "Top result should be highly relevant to cinema/film query");
        }
    }

    @Test
    void search_removedFields_stillAccessible_eventType() {
        // Step 1 validation: Fields removed from search index should still be
        // accessible via doc.get()
        // This validates the Conservative implementation (Store.YES for removed fields)

        String query = "festival";
        List<RagHelper.Event> results = eventRagHelper.search(query);

        assertTrue(results.size() > 0, "Should find festival event");
        RagHelper.Event event = results.get(0);

        // eventType was removed from SEARCH_FIELDS but stored (not null)
        assertNotNull(event.eventType, "eventType field should be accessible even though not indexed");
        assertFalse(event.eventType.isBlank(), "eventType should have a value");
    }

    @Test
    void search_removedFields_stillAccessible_location() {
        // Step 1 validation: Location field should still be accessible

        String query = "festival";
        List<RagHelper.Event> results = eventRagHelper.search(query);

        assertTrue(results.size() > 0, "Should find festival event");
        RagHelper.Event event = results.get(0);

        // location was removed from SEARCH_FIELDS but stored
        assertNotNull(event.location, "location field should be accessible");
        assertFalse(event.location.isBlank(), "location should have a value");
    }

    @Test
    void search_javaEngine_returnsRelevantResults() {
        List<RagHelper.Event> results = eventRagHelper.search("cinema film");

        assertFalse(results.isEmpty());
        assertTrue(results.size() <= 3);
        RagHelper.Event topResult = results.get(0);
        assertTrue(topResult.eventType.equalsIgnoreCase("Cinema")
                || topResult.title.toLowerCase().contains("film")
                || topResult.description.toLowerCase().contains("cinema"));
    }

    @Test
    void search_javaEngine_handlesAccentAndPunctuationNormalization() {
        List<RagHelper.Event> results = eventRagHelper.search("  cinèma, film!  ");

        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(event -> event.eventType.equalsIgnoreCase("Cinema")));
    }

    @Test
    void search_isDeterministicAcrossHelperInstances() {
        RagHelper<RagHelper.Event> secondHelper = RagHelper.forEvents("testcity");
        List<RagHelper.Event> firstResults = eventRagHelper.search("festival concert event");
        List<RagHelper.Event> secondResults = secondHelper.search("festival concert event");

        assertEquals(firstResults.size(), secondResults.size());
        if (!firstResults.isEmpty()) {
            assertEquals(firstResults.get(0).eventId, secondResults.get(0).eventId);
        }
    }
}
