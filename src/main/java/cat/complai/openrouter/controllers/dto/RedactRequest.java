package cat.complai.openrouter.controllers.dto;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class RedactRequest {
    private String text;

    public RedactRequest(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

}

