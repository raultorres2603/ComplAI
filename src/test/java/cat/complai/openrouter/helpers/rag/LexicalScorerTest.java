package cat.complai.openrouter.helpers.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalScorerTest {

    private final LexicalScorer scorer = new LexicalScorer();

    @Test
    void bm25TermScore_rewardsHigherTermFrequency() {
        double lowTf = scorer.bm25TermScore(1, 1, 3, 10, 10, 8.0d, 1.0d);
        double highTf = scorer.bm25TermScore(3, 1, 3, 10, 10, 8.0d, 1.0d);
        assertTrue(highTf > lowTf);
    }

    @Test
    void bm25TermScore_appliesFieldWeight() {
        double titleWeighted = scorer.bm25TermScore(1, 1, 3, 10, 10, 8.0d, 2.0d);
        double descriptionWeighted = scorer.bm25TermScore(1, 1, 3, 10, 10, 8.0d, 1.0d);
        assertTrue(titleWeighted > descriptionWeighted);
    }

    @Test
    void bm25TermScore_returnsZeroForNonMatchingTerm() {
        double score = scorer.bm25TermScore(0, 1, 1, 10, 5, 5.0d, 1.0d);
        assertTrue(score == 0.0d);
    }

    @Test
    void bm25TermScore_respectsConfiguredScorerParameters() {
        LexicalScorer tunedScorer = new LexicalScorer(new RagJavaCalibration.ScorerSettings(1.8d, 0.5d, 0.1d));

        double defaultScore = scorer.bm25TermScore(2, 1, 3, 10, 10, 8.0d, 1.0d);
        double tunedScore = tunedScorer.bm25TermScore(2, 1, 3, 10, 10, 8.0d, 1.0d);

        assertTrue(tunedScore != defaultScore);
    }
}
