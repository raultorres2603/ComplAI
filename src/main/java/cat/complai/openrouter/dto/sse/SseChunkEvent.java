package cat.complai.openrouter.dto.sse;

import io.micronaut.core.annotation.Introspected;

/**
 * SSE event for LLM response chunk (text delta).
 * Serialized as JSON: {"type":"chunk","content":"..."}
 */
@Introspected
public record SseChunkEvent(String type, String content) {
    public SseChunkEvent(String content) {
        this("chunk", content);
    }
}
