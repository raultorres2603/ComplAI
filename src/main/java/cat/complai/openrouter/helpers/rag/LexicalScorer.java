package cat.complai.openrouter.helpers.rag;

/**
 * BM25 term-level scorer used by {@link InMemoryLexicalIndex}.
 *
 * <p>Implements the Okapi BM25 ranking function with configurable {@code k1} and {@code b}
 * parameters (loaded from {@link RagJavaCalibration}). Each scored term contribution is
 * multiplied by the field's importance weight and the query term frequency, producing a
 * per-term score that the index accumulates across all query tokens and document fields.
 *
 * <p>Scoring parameters can be overridden at runtime via system properties
 * ({@code rag.retrieval.java.scorer.k1}, etc.) for calibration experiments without
 * redeploying.
 */
public final class LexicalScorer {

    private final double bm25K1;
    private final double bm25B;
    private final double idfSmoothing;

    public LexicalScorer() {
        this(RagJavaCalibration.scorer());
    }

    public LexicalScorer(RagJavaCalibration.ScorerSettings settings) {
        this.bm25K1 = settings.k1();
        this.bm25B = settings.b();
        this.idfSmoothing = settings.idfSmoothing();
    }

    /**
     * Computes the BM25 score contribution for a single query term in a single document field.
     *
     * @param termFrequencyInDocField  number of times the query term appears in this document field
     * @param queryTermFrequency       number of times the query term appears in the query itself
     * @param documentFrequency        number of documents in the corpus containing the term
     * @param totalDocuments           total number of documents in the corpus
     * @param fieldLength              number of tokens in this document field
     * @param averageFieldLength       average field length across the entire corpus
     * @param fieldWeight              importance weight assigned to this field (e.g. 2.0 for title)
     * @return the BM25 score contribution; 0.0 if any frequency argument is non-positive or
     *         the field weight is zero
     */
    public double bm25TermScore(int termFrequencyInDocField,
                                int queryTermFrequency,
                                int documentFrequency,
                                int totalDocuments,
                                int fieldLength,
                                double averageFieldLength,
                                double fieldWeight) {
        if (termFrequencyInDocField <= 0 || queryTermFrequency <= 0 || totalDocuments <= 0 || fieldWeight <= 0.0d) {
            return 0.0d;
        }

        double avgLength = averageFieldLength <= 0.0d ? 1.0d : averageFieldLength;
        double normalizedLength = fieldLength <= 0 ? 1.0d : fieldLength;
        double smoothing = Math.max(0.0d, idfSmoothing);

        double idf = Math.log1p((totalDocuments - documentFrequency + smoothing) / (documentFrequency + smoothing));
        double denominator = termFrequencyInDocField
            + bm25K1 * (1.0d - bm25B + bm25B * (normalizedLength / avgLength));
        double tfComponent = (termFrequencyInDocField * (bm25K1 + 1.0d)) / denominator;

        return fieldWeight * idf * tfComponent * queryTermFrequency;
    }
}
