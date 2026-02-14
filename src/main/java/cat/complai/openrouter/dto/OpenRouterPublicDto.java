package cat.complai.openrouter.dto;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class OpenRouterPublicDto {
    private final boolean success;
    private final String message;
    private final String error;
    private final OpenRouterErrorCode errorCode;

    public OpenRouterPublicDto(boolean success, String message, String error, OpenRouterErrorCode errorCode) {
        this.success = success;
        this.message = message;
        this.error = error;
        this.errorCode = errorCode == null ? OpenRouterErrorCode.NONE : errorCode;
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

    public static OpenRouterPublicDto from(OpenRouterResponseDto dto) {
        if (dto == null) return null;
        return new OpenRouterPublicDto(dto.isSuccess(), dto.getMessage(), dto.getError(), dto.getErrorCode());
    }
}

