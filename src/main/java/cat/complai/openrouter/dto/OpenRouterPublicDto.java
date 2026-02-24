package cat.complai.openrouter.dto;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class OpenRouterPublicDto {
    private final boolean success;
    private final String message;
    private final String error;
    private final int errorCode; // numeric code for external clients

    public OpenRouterPublicDto(boolean success, String message, String error, int errorCode) {
        this.success = success;
        this.message = message;
        this.error = error;
        this.errorCode = errorCode;
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

    public int getErrorCode() {
        return errorCode;
    }

    public static OpenRouterPublicDto from(OpenRouterResponseDto dto) {
        if (dto == null) return null;
        int code = dto.getErrorCode() == null ? 0 : dto.getErrorCode().getCode();
        return new OpenRouterPublicDto(dto.isSuccess(), dto.getMessage(), dto.getError(), code);
    }
}
