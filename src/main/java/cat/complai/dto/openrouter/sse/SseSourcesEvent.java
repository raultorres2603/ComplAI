package cat.complai.dto.openrouter.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import java.util.Collections;
import java.util.List;

/**
 * SSE event for RAG sources (array of procedures and events).
 * Serialized as JSON: {"type":"sources","sources":[{"title":"...","url":"..."},{},...]}
 */
@Introspected
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SseSourcesEvent(
        String type,
        @JsonProperty("sources")
        List<SseSources> sources) {
    
    public SseSourcesEvent(List<SseSources> sources) {
        this("sources", sources != null ? sources : Collections.emptyList());
    }
}
