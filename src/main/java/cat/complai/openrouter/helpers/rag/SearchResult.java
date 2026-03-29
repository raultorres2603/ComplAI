package cat.complai.openrouter.helpers.rag;

public record SearchResult<T>(T source, double score, int sourceOrder) {
}
