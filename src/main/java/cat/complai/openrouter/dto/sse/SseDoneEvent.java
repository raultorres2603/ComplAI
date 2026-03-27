package cat.complai.openrouter.dto.sse;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * SSE event for stream completion.
 * Serialized as JSON: {"type":"done","conversationId":"..."}
 */
@Introspected
public record SseDoneEvent(String type, @Nullable String conversationId) {
    public SseDoneEvent(@Nullable String conversationId) {
        this("done", conversationId);
    }
}
