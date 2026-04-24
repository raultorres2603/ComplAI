package cat.complai.dto.openrouter;

import io.micronaut.core.annotation.Introspected;

/**
 * A source document referenced in an AI response, carrying a URL and a
 * human-readable title.
 */
@Introspected
public class Source {
    private final String url;
    private final String title;

    /**
     * Constructs a {@code Source} with the given URL and title.
     *
     * @param url   the URL of the source document
     * @param title the display title of the source document
     */
    public Source(String url, String title) {
        this.url = url;
        this.title = title;
    }

    /**
     * Returns the URL of the source document.
     *
     * @return source URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Returns the display title of the source document.
     *
     * @return source title
     */
    public String getTitle() {
        return title;
    }
}
