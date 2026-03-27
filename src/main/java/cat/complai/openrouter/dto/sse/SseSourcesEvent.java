package cat.complai.openrouter.dto.sse;

import io.micronaut.core.annotation.Introspected;
import java.util.List;

/**
 * SSE event for RAG sources (array of procedures and events).
 * Serialized as JSON: {"type":"sources","sources":[{"title":"...","url":"..."},{},...]}
 */
@Introspected
public record SseSourcesEvent(String type, List<SseSources> sources) {
    public SseSourcesEvent(List<SseSources> sources) {
        this("sources", sources);
    }
}
