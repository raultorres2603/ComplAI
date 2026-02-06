package cat.complai.home.dto;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class HomeDto {

    private final String message;
    private final long timestamp;

    public HomeDto(String message, long timestamp) {
        this.message = message;
        this.timestamp = timestamp;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestamp() {
        return timestamp;
    }
}

