package cat.complai.dto.openrouter.sse;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * Represents a single source (procedure or event) in an SSE sources event.
 * Maps from Source DTO.
 */
@Introspected
public record SseSources(String title, @Nullable String url) {
}
