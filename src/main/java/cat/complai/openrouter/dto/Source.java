package cat.complai.openrouter.dto;

import io.micronaut.core.annotation.Introspected;

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
