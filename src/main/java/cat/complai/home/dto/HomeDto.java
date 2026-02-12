package cat.complai.home.dto;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class HomeDto {

    private final String message;

    public HomeDto(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

}

