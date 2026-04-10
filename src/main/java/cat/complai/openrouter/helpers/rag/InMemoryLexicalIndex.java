package cat.complai.openrouter.helpers.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable BM25 retrieval index that lives entirely in the JVM heap.
 *
 * <p>
 * Documents are tokenised once at build time; scoring happens at query time
 * without any
 * external library (no Lucene, no Elasticsearch). Supports optional
 * per-document language
 * tagging for language-aware score boosting.
 *
 * @param <T> the domain type stored in each indexed document
 */
public final class InMemoryLexicalIndex<T> {

    private final List<IndexedDocument<T>> documents;
    private final Map<String, Double> averageFieldLength;
    private final Map<String, Map<String, Integer>> docFrequencyByField;
    private final Map<String, Double> fieldWeights;
    private final LexicalScorer lexicalScorer;

    /**
     * Constructs an index from pre-computed document statistics.
     *
     * @param documents           list of indexed documents
     * @param averageFieldLength  average token count per field, keyed by field name
     * @param docFrequencyByField per-field document-frequency map, keyed by field
     *                            then term
     * @param fieldWeights        relative scoring weight for each field
     * @param lexicalScorer       BM25 scorer used at query time
     */
    public InMemoryLexicalIndex(List<IndexedDocument<T>> documents,
            Map<String, Double> averageFieldLength,
            Map<String, Map<String, Integer>> docFrequencyByField,
            Map<String, Double> fieldWeights,
            LexicalScorer lexicalScorer) {
        this.documents = List.copyOf(documents);
        this.averageFieldLength = Map.copyOf(averageFieldLength);
        this.docFrequencyByField = Map.copyOf(docFrequencyByField);
        this.fieldWeights = Map.copyOf(fieldWeights);
        this.lexicalScorer = Objects.requireNonNull(lexicalScorer, "lexicalScorer");
    }

    /**
     * Builds an index from a list of entities, extracting field text with the
     * provided
     * function.
     *
     * @param <T>            the domain type to index
     * @param entities       entities to index
     * @param fieldWeights   scoring weight for each field, keyed by field name
     * @param fieldExtractor function that maps an entity to its fields (field name
     *                       → text)
     * @return a ready-to-search {@link InMemoryLexicalIndex}
     */
    public static <T> InMemoryLexicalIndex<T> build(List<T> entities,
            Map<String, Double> fieldWeights,
            Function<T, Map<String, String>> fieldExtractor) {
        List<IndexedDocument<T>> docs = new ArrayList<>();
        Map<String, Integer> totalFieldLengths = new HashMap<>();
        Map<String, Map<String, Integer>> docFrequencies = new HashMap<>();

        int sourceOrder = 0;
        for (T entity : entities) {
            Map<String, String> fields = fieldExtractor.apply(entity);
            Map<String, List<String>> fieldTokens = new LinkedHashMap<>();
            Map<String, Integer> fieldLengths = new LinkedHashMap<>();
            Map<String, Map<String, Integer>> termFrequenciesByField = new LinkedHashMap<>();

            for (Map.Entry<String, Double> weightedField : fieldWeights.entrySet()) {
                String fieldName = weightedField.getKey();
                String rawValue = fields.getOrDefault(fieldName, "");
                List<String> tokens = TokenNormalizer.tokenize(rawValue);
                fieldTokens.put(fieldName, tokens);
                fieldLengths.put(fieldName, tokens.size());
                int currentFieldLength = totalFieldLengths.getOrDefault(fieldName, 0);
                totalFieldLengths.put(fieldName, currentFieldLength + tokens.size());

                Map<String, Integer> termFrequency = new HashMap<>();
                for (String token : tokens) {
                    int currentFrequency = termFrequency.getOrDefault(token, 0);
                    termFrequency.put(token, currentFrequency + 1);
                }
                termFrequenciesByField.put(fieldName, Map.copyOf(termFrequency));

                for (String token : termFrequency.keySet()) {
                    Map<String, Integer> byField = docFrequencies.computeIfAbsent(fieldName,
                            ignored -> new HashMap<>());
                    int currentDocFrequency = byField.getOrDefault(token, 0);
                    byField.put(token, currentDocFrequency + 1);
                }
            }

            docs.add(new IndexedDocument<>(
                    sourceOrder++,
                    entity,
                    Map.copyOf(fieldTokens),
                    Map.copyOf(fieldLengths),
                    Map.copyOf(termFrequenciesByField)));
        }

        int totalDocuments = Math.max(1, docs.size());
        Map<String, Double> averageFieldLength = new HashMap<>();
        for (String fieldName : fieldWeights.keySet()) {
            averageFieldLength.put(fieldName, totalFieldLengths.getOrDefault(fieldName, 0) / (double) totalDocuments);
        }

        Map<String, Map<String, Integer>> immutableDocFrequencies = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : docFrequencies.entrySet()) {
            immutableDocFrequencies.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }

        return new InMemoryLexicalIndex<>(
                docs,
                averageFieldLength,
                immutableDocFrequencies,
                fieldWeights,
                new LexicalScorer());
    }

    /**
     * Builds an index from a list of entities with optional per-document language
     * detection.
     *
     * @param <T>               the domain type to index
     * @param entities          entities to index
     * @param fieldWeights      scoring weight for each field
     * @param fieldExtractor    maps an entity to its fields
     * @param languageExtractor maps an entity to its ISO language tag
     * @param languageTags      expected language tags for token normalisation
     * @return a ready-to-search {@link InMemoryLexicalIndex}
     */
    public static <T> InMemoryLexicalIndex<T> build(List<T> entities,
            Map<String, Double> fieldWeights,
            Function<T, Map<String, String>> fieldExtractor,
            Function<T, String> languageExtractor,
            String... languageTags) {
        List<IndexedDocument<T>> docs = new ArrayList<>();
        Map<String, Integer> totalFieldLengths = new HashMap<>();
        Map<String, Map<String, Integer>> docFrequencies = new HashMap<>();

        int sourceOrder = 0;
        for (T entity : entities) {
            Map<String, String> fields = fieldExtractor.apply(entity);
            Map<String, List<String>> fieldTokens = new LinkedHashMap<>();
            Map<String, Integer> fieldLengths = new LinkedHashMap<>();
            Map<String, Map<String, Integer>> termFrequenciesByField = new LinkedHashMap<>();

            for (Map.Entry<String, Double> weightedField : fieldWeights.entrySet()) {
                String fieldName = weightedField.getKey();
                String rawValue = fields.getOrDefault(fieldName, "");
                List<String> tokens = TokenNormalizer.tokenize(rawValue);
                fieldTokens.put(fieldName, tokens);
                fieldLengths.put(fieldName, tokens.size());
                int currentFieldLength = totalFieldLengths.getOrDefault(fieldName, 0);
                totalFieldLengths.put(fieldName, currentFieldLength + tokens.size());

                Map<String, Integer> termFrequency = new HashMap<>();
                for (String token : tokens) {
                    int currentFrequency = termFrequency.getOrDefault(token, 0);
                    termFrequency.put(token, currentFrequency + 1);
                }
                termFrequenciesByField.put(fieldName, Map.copyOf(termFrequency));

                for (String token : termFrequency.keySet()) {
                    Map<String, Integer> byField = docFrequencies.computeIfAbsent(fieldName,
                            ignored -> new HashMap<>());
                    int currentDocFrequency = byField.getOrDefault(token, 0);
                    byField.put(token, currentDocFrequency + 1);
                }
            }

            String docLanguage = languageExtractor.apply(entity);
            docs.add(new IndexedDocument<>(
                    sourceOrder++,
                    entity,
                    Map.copyOf(fieldTokens),
                    Map.copyOf(fieldLengths),
                    Map.copyOf(termFrequenciesByField),
                    docLanguage != null ? docLanguage : "CA"));
        }

        int totalDocuments = Math.max(1, docs.size());
        Map<String, Double> averageFieldLength = new HashMap<>();
        for (String fieldName : fieldWeights.keySet()) {
            averageFieldLength.put(fieldName, totalFieldLengths.getOrDefault(fieldName, 0) / (double) totalDocuments);
        }

        Map<String, Map<String, Integer>> immutableDocFrequencies = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : docFrequencies.entrySet()) {
            immutableDocFrequencies.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }

        return new InMemoryLexicalIndex<>(
                docs,
                averageFieldLength,
                immutableDocFrequencies,
                fieldWeights,
                new LexicalScorer());
    }

    /**
     * Searches the index with a plain-text query string and returns the top-K
     * results.
     *
     * @param query         natural-language query
     * @param topK          maximum number of results to return
     * @param absoluteFloor minimum absolute BM25 score a result must exceed
     * @param relativeFloor minimum score as a fraction of the top-scoring result
     * @return a {@link SearchResponse} containing the ranked results
     */
    public SearchResponse<T> search(String query, int topK, double absoluteFloor, double relativeFloor) {
        List<String> queryTokens = TokenNormalizer.tokenize(query);
        if (queryTokens.isEmpty() || topK <= 0 || documents.isEmpty()) {
            return SearchResponse.empty(absoluteFloor, relativeFloor);
        }

        return search(queryTokens, topK, absoluteFloor, relativeFloor);
    }

    /**
     * Searches the index with pre-tokenised query tokens.
     *
     * @param queryTokens   tokens derived from the user query
     * @param topK          maximum number of results
     * @param absoluteFloor minimum absolute BM25 score
     * @param relativeFloor minimum score relative to the top result
     * @return a {@link SearchResponse} containing the ranked results
     */
    public SearchResponse<T> search(List<String> queryTokens, int topK, double absoluteFloor, double relativeFloor) {
        return search(queryTokens, topK, absoluteFloor, relativeFloor, null);
    }

    /**
     * Searches the index with pre-tokenised query tokens and optional language
     * boosting.
     *
     * @param queryTokens   tokens derived from the user query
     * @param topK          maximum number of results
     * @param absoluteFloor minimum absolute BM25 score
     * @param relativeFloor minimum score relative to the top result
     * @param queryLanguage ISO language tag used for language-aware score boosting;
     *                      may be {@code null} to disable boosting
     * @return a {@link SearchResponse} containing the ranked results
     */
    public SearchResponse<T> search(List<String> queryTokens, int topK, double absoluteFloor, double relativeFloor,
            String queryLanguage) {
        if (queryTokens.isEmpty() || topK <= 0 || documents.isEmpty()) {
            return SearchResponse.empty(absoluteFloor, relativeFloor);
        }

        Map<String, Integer> queryTermFrequency = DeterministicQueryExpansion.termFrequency(queryTokens);

        List<SearchResult<T>> scoredResults = new ArrayList<>();
        int totalDocuments = documents.size();

        for (IndexedDocument<T> document : documents) {
            double score = 0.0d;

            for (Map.Entry<String, Double> fieldWeight : fieldWeights.entrySet()) {
                String fieldName = fieldWeight.getKey();
                double weight = fieldWeight.getValue();
                Map<String, Integer> fieldTermFrequency = document.fieldTermFrequency().getOrDefault(fieldName,
                        Map.of());
                int fieldLength = document.fieldLengths().getOrDefault(fieldName, 0);
                double avgFieldLength = averageFieldLength.getOrDefault(fieldName, 1.0d);
                Map<String, Integer> fieldDocFrequency = docFrequencyByField.getOrDefault(fieldName, Map.of());

                for (Map.Entry<String, Integer> queryEntry : queryTermFrequency.entrySet()) {
                    String term = queryEntry.getKey();
                    int queryTf = queryEntry.getValue();
                    int docTf = fieldTermFrequency.getOrDefault(term, 0);
                    int docFrequency = fieldDocFrequency.getOrDefault(term, 0);
                    score += lexicalScorer.bm25TermScore(
                            docTf,
                            queryTf,
                            docFrequency,
                            totalDocuments,
                            fieldLength,
                            avgFieldLength,
                            weight);
                }
            }

            // Apply language boost if query language matches document language
            if (score > 0.0d && queryLanguage != null && !queryLanguage.isEmpty()) {
                String docLanguage = document.language();
                if (docLanguage != null && queryLanguage.equalsIgnoreCase(docLanguage)) {
                    score *= 1.5d;
                }
            }

            if (score > 0.0d) {
                scoredResults.add(new SearchResult<>(document.source(), score, document.sourceOrder()));
            }
        }

        if (scoredResults.isEmpty()) {
            return SearchResponse.empty(absoluteFloor, relativeFloor);
        }

        scoredResults.sort(Comparator
                .comparingDouble(SearchResult<T>::score)
                .reversed()
                .thenComparingInt(SearchResult<T>::sourceOrder));

        double bestScore = scoredResults.get(0).score();
        double appliedThreshold = Math.max(absoluteFloor, bestScore * relativeFloor);

        List<SearchResult<T>> accepted = scoredResults.stream()
                .filter(result -> result.score() >= appliedThreshold)
                .limit(topK)
                .toList();

        int filteredCount = scoredResults.size() - accepted.size();
        return new SearchResponse<>(accepted, filteredCount, scoredResults.size(), bestScore,
                absoluteFloor, relativeFloor, appliedThreshold);
    }

    public record SearchResponse<T>(
            List<SearchResult<T>> results,
            int filteredCount,
            int candidateCount,
            double bestScore,
            double absoluteFloor,
            double relativeFloor,
            double appliedThreshold) {

        static <T> SearchResponse<T> empty(double absoluteFloor, double relativeFloor) {
            return new SearchResponse<>(List.of(), 0, 0, 0.0d, absoluteFloor, relativeFloor, absoluteFloor);
        }
    }
}
