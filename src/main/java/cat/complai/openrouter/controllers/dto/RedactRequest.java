package cat.complai.openrouter.controllers.dto;

import io.micronaut.core.annotation.Introspected;
import cat.complai.openrouter.dto.OutputFormat;

@Introspected
public class RedactRequest {
    private final String text;
    private OutputFormat format = OutputFormat.AUTO;

    public RedactRequest(String text) {
        this.text = text;
    }

    public RedactRequest(String text, OutputFormat format) {
        this.text = text;
        // Preserve null: a null format here means the client sent an unrecognised value.
        // The controller checks OutputFormat.isSupportedClientFormat() and rejects it before
        // the service is ever called.
        this.format = format;
    }

    public String getText() {
        return text;
    }

    public OutputFormat getFormat() {
        return format;
    }

}
