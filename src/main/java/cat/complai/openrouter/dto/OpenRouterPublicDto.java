package cat.complai.openrouter.dto;

import io.micronaut.core.annotation.Introspected;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Public-facing response DTO serialised to JSON for all API responses.
 *
 * <p>A subset of {@link OpenRouterResponseDto}: PDF bytes are stripped (never sent over the
 * wire in this form) and the error code is surfaced as a plain integer for easy parsing by
 * front-end clients. Use {@link #from(OpenRouterResponseDto)} to convert from the internal DTO.
 */
@Introspected
public class OpenRouterPublicDto {
    private final boolean success;
    private final String message;
    private final String error;
    private final int errorCode; // numeric code for external clients
    private final List<Source> sources;

    public OpenRouterPublicDto(boolean success, String message, String error, int errorCode, List<Source> sources) {
        this.success = success;
        this.message = message;
        this.error = error;
        this.errorCode = errorCode;
        this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
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

    public List<Source> getSources() {
        return sources;
    }

    public static OpenRouterPublicDto from(OpenRouterResponseDto dto) {
        if (dto == null) return null;
        int code = dto.getErrorCode() == null ? 0 : dto.getErrorCode().getCode();
        return new OpenRouterPublicDto(dto.isSuccess(), dto.getMessage(), dto.getError(), code, dto.getSources());
    }
}
