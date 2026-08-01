package cat.complai.dto.openrouter.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * SSE event for stream completion.
 * Serialized as JSON: {"type":"done","conversationId":"..."}
 * Omits conversationId if null or empty.
 */
@Introspected
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SseDoneEvent(String type, @Nullable String conversationId) {
    public SseDoneEvent(@Nullable String conversationId) {
        this("done", conversationId);
    }
}
