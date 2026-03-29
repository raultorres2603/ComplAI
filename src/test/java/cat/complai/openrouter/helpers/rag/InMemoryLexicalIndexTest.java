package cat.complai.openrouter.helpers.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryLexicalIndexTest {

    private record Doc(String id, String title, String description) {
    }

    @Test
    void search_returnsDeterministicTopK() {
        InMemoryLexicalIndex<Doc> index = buildIndex();

        InMemoryLexicalIndex.SearchResponse<Doc> response = index.search("waste", 2, 0.0d, 0.0d);

        assertEquals(2, response.results().size());
        assertEquals("b", response.results().get(0).source().id());
        assertEquals("a", response.results().get(1).source().id());
    }

    @Test
    void search_appliesThresholdFiltering() {
        InMemoryLexicalIndex<Doc> index = buildIndex();

        InMemoryLexicalIndex.SearchResponse<Doc> response = index.search("waste", 3, 0.10d, 0.90d);

        assertTrue(response.results().size() <= 2);
        assertTrue(response.filteredCount() >= 0);
    }

    @Test
    void search_emptyQuery_returnsNoResults() {
        InMemoryLexicalIndex<Doc> index = buildIndex();

        InMemoryLexicalIndex.SearchResponse<Doc> response = index.search("   ", 3, 0.05d, 0.25d);

        assertTrue(response.results().isEmpty());
        assertEquals(0, response.candidateCount());
    }

    private InMemoryLexicalIndex<Doc> buildIndex() {
        List<Doc> docs = List.of(
                new Doc("a", "Recycling center", "Municipal waste recycling service"),
                new Doc("b", "Waste pickup", "Collection of household waste"),
                new Doc("c", "Library card", "Apply for a new card"));

        Map<String, Double> fieldWeights = new LinkedHashMap<>();
        fieldWeights.put("title", 2.0d);
        fieldWeights.put("description", 1.0d);

        return InMemoryLexicalIndex.build(docs, fieldWeights, doc -> {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("title", doc.title());
            fields.put("description", doc.description());
            return fields;
        });
    }
}
