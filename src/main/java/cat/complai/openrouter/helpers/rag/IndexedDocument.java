package cat.complai.openrouter.helpers.rag;

import java.util.List;
import java.util.Map;

public record IndexedDocument<T>(
        int sourceOrder,
        T source,
        Map<String, List<String>> fieldTokens,
        Map<String, Integer> fieldLengths,
        Map<String, Map<String, Integer>> fieldTermFrequency) {
}
