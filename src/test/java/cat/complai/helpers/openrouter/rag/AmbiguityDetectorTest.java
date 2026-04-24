package cat.complai.helpers.openrouter.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmbiguityDetectorTest {

    private static InMemoryLexicalIndex.SearchResponse<String> response(double... scores) {
        List<SearchResult<String>> results = new java.util.ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            results.add(new SearchResult<>("item" + i, scores[i], i));
        }
        double best = scores.length > 0 ? scores[0] : 0.0d;
        return new InMemoryLexicalIndex.SearchResponse<>(results, 0, scores.length, best, 0.15d, 0.45d, 0.15d);
    }

    @Test
    void isAmbiguous_returnsFalse_whenOnlyOneResult() {
        assertFalse(AmbiguityDetector.isAmbiguous(response(1.0), 0.15));
    }

    @Test
    void isAmbiguous_returnsFalse_whenScoreRatioBelowThreshold() {
        assertFalse(AmbiguityDetector.isAmbiguous(response(1.0, 0.5), 0.15));
    }

    @Test
    void isAmbiguous_returnsTrue_whenScoresAreNearlyEqual() {
        assertTrue(AmbiguityDetector.isAmbiguous(response(1.0, 0.9), 0.15));
    }

    @Test
    void isAmbiguous_returnsFalse_whenScoresBelowAbsoluteFloor() {
        assertFalse(AmbiguityDetector.isAmbiguous(response(0.05, 0.04), 0.15));
    }

    @Test
    void getTopCandidates_respectsN_andNeverExceedsResultsSize() {
        List<SearchResult<String>> top2 = AmbiguityDetector.getTopCandidates(response(1.0, 0.9, 0.8), 2);
        assertEquals(2, top2.size());
        assertEquals("item0", top2.get(0).source());
        assertEquals("item1", top2.get(1).source());

        List<SearchResult<String>> top10 = AmbiguityDetector.getTopCandidates(response(1.0, 0.9), 10);
        assertEquals(2, top10.size());
    }
}
