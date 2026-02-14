package cat.complai.openrouter.controllers.dto;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class AskRequest {
    private String text;

    public AskRequest() {
    }

    public AskRequest(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}

