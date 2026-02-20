package cat.complai.openrouter.controllers.dto;

import io.micronaut.core.annotation.Introspected;
import cat.complai.openrouter.dto.OutputFormat;

@Introspected
public class RedactRequest {
    private String text;
    private OutputFormat format = OutputFormat.AUTO;

    public RedactRequest(String text) {
        this.text = text;
    }

    public RedactRequest(String text, OutputFormat format) {
        this.text = text;
        this.format = format == null ? OutputFormat.AUTO : format;
    }

    public String getText() {
        return text;
    }

    public OutputFormat getFormat() {
        return format;
    }

}
