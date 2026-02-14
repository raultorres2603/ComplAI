package cat.complai.openrouter.controllers.dto;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class RedactRequest {
    private String text;

    public RedactRequest() {
    }

    public RedactRequest(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}

