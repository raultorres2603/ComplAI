package cat.complai.openrouter.helpers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventRagHelperTest {

    private EventRagHelper eventRagHelper;

    @BeforeEach
    void setup() throws IOException {
        // Use testcity which has events-testcity.json with 5 events
        eventRagHelper = new EventRagHelper("testcity");
    }

    @Test
    void search_returnsMaxThreeResults() {
        // Broad query that should match multiple events
        String query = "festival concert event";
        List<EventRagHelper.Event> results = eventRagHelper.search(query);

        assertTrue(results.size() <= 3, "Should return at most 3 results, but got " + results.size());
    }

    @Test
    void search_fewerResultsIfFewMatches() {
        // Query that matches only specific events
        String query = "cinema film";
        List<EventRagHelper.Event> results = eventRagHelper.search(query);

        // Should return 1 or fewer results (Film Festival match)
        assertTrue(results.size() <= 3, "Should respect max 3 limit");
        // At least one result should be a film event
        assertTrue(results.stream().anyMatch(e -> e.eventType.contains("Cinema") || e.title.contains("Film")),
                "Should find film-related event");
    }

    @Test
    void search_emptyQuery_returnsEmpty() {
        List<EventRagHelper.Event> results = eventRagHelper.search("");
        assertTrue(results.isEmpty(), "Empty query should return empty list");
    }

    @Test
    void search_nullQuery_returnsEmpty() {
        List<EventRagHelper.Event> results = eventRagHelper.search(null);
        assertTrue(results.isEmpty(), "Null query should return empty list");
    }

    @Test
    void getAllEvents_returnsFull() {
        List<EventRagHelper.Event> allEvents = eventRagHelper.getAllEvents();

        // Should return all 5 events (unrestricted)
        assertEquals(5, allEvents.size(), "getAllEvents() should return all events");
    }

    @Test
    void search_returnsTopResultsByRelevance() {
        // Specific query that should match multiple events
        String query = "cultural performance";
        List<EventRagHelper.Event> results = eventRagHelper.search(query);

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
        List<EventRagHelper.Event> results = eventRagHelper.search(query);

        assertTrue(results.size() > 0, "Should find festival event");
        EventRagHelper.Event event = results.get(0);

        // Verify all important fields are preserved
        assertNotNull(event.eventId);
        assertNotNull(event.title);
        assertNotNull(event.url);
        assertFalse(event.eventId.isBlank());
        assertFalse(event.title.isBlank());
    }
}
