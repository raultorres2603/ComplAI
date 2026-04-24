package cat.complai.helpers.openrouter.rag;

import java.util.List;
import java.util.Map;

public record IndexedDocument<T>(
        int sourceOrder,
        T source,
        Map<String, List<String>> fieldTokens,
        Map<String, Integer> fieldLengths,
        Map<String, Map<String, Integer>> fieldTermFrequency,
        String language) {
    
    public IndexedDocument(
            int sourceOrder,
            T source,
            Map<String, List<String>> fieldTokens,
            Map<String, Integer> fieldLengths,
            Map<String, Map<String, Integer>> fieldTermFrequency) {
        this(sourceOrder, source, fieldTokens, fieldLengths, fieldTermFrequency, "CA");
    }
}
