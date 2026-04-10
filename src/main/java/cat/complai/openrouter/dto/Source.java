package cat.complai.openrouter.dto;

import io.micronaut.core.annotation.Introspected;

/**
 * A URL-and-title pair representing a source document retrieved by the RAG index.
 *
 * <p>Sources are included in SSE {@code sources} events and in the final {@code complete}
 * event so the front-end can display clickable citations alongside the AI response.
 */
@Introspected
public class Source {
    private final String url;
    private final String title;

    public Source(String url, String title) {
        this.url = url;
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }
}
