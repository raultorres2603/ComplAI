package cat.complai.openrouter.helpers.rag;

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
