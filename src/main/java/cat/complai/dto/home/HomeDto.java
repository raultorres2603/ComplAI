package cat.complai.dto.home;

import io.micronaut.core.annotation.Introspected;

/**
 * DTO for the root endpoint response.
 */
@Introspected
public class HomeDto {

    private final String message;

    /**
     * Constructs a {@code HomeDto} with the given welcome message.
     *
     * @param message the welcome message to return
     */
    public HomeDto(String message) {
        this.message = message;
    }

    /**
     * Returns the welcome message.
     *
     * @return welcome message string
     */
    public String getMessage() {
        return message;
    }

}
