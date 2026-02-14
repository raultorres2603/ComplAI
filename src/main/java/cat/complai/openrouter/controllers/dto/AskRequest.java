package cat.complai.openrouter.controllers.dto;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class AskRequest {
    private final String text;

    public AskRequest(String text) {
        this.text = text;
    }

    public String getText() {
        return text;
    }

}

