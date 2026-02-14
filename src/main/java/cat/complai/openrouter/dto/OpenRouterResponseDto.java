package cat.complai.openrouter.dto;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class OpenRouterResponseDto {
    private final boolean success;
    private final String message; // AI message when success
    private final String error;   // Error message when not success
    private final Integer statusCode; // HTTP status code from AI service when available

    public OpenRouterResponseDto(boolean success, String message, String error, Integer statusCode) {
        this.success = success;
        this.message = message;
        this.error = error;
        this.statusCode = statusCode;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public String getError() {
        return error;
    }

    public Integer getStatusCode() {
        return statusCode;
    }
}

