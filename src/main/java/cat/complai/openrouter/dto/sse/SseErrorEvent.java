package cat.complai.openrouter.dto.sse;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * SSE event for errors during streaming.
 * Serialized as JSON: {"type":"error","error":"...","errorCode":1}
 */
@Introspected
public record SseErrorEvent(String type, String error, @Nullable Integer errorCode) {
    public SseErrorEvent(String error, @Nullable Integer errorCode) {
        this("error", error, errorCode);
    }

    public SseErrorEvent(String error) {
        this("error", error, null);
    }
}
