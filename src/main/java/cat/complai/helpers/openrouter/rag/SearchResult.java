package cat.complai.helpers.openrouter.rag;

public record SearchResult<T>(T source, double score, int sourceOrder) {
}
