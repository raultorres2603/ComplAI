package cat.complai.dto.openrouter;

import io.micronaut.core.annotation.Introspected;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * External-facing DTO returned to API clients from the {@code /complai}
 * endpoints.
 *
 * <p>
 * Unlike {@link OpenRouterResponseDto} this DTO omits raw PDF bytes and
 * normalises the
 * error code to a plain {@code int} so clients can rely on a stable JSON shape.
 */
@Introspected
public class OpenRouterPublicDto {
    private final boolean success;
    private final String message;
    private final String error;
    private final int errorCode; // numeric code for external clients
    private final List<Source> sources;

    /**
     * Constructs a public DTO with all fields.
     *
     * @param success   {@code true} if the request succeeded
     * @param message   the AI-generated text, or {@code null} on failure
     * @param error     the error description, or {@code null} on success
     * @param errorCode numeric error code for external clients (0 = no error)
     * @param sources   list of RAG source documents cited in the response
     */
    public OpenRouterPublicDto(boolean success, String message, String error, int errorCode, List<Source> sources) {
        this.success = success;
        this.message = message;
        this.error = error;
        this.errorCode = errorCode;
        this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
    }

    /**
     * Returns {@code true} if the request completed successfully.
     *
     * @return success flag
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Returns the AI-generated message text, or {@code null} on failure.
     *
     * @return AI message text
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the error description, or {@code null} on success.
     *
     * @return error description
     */
    public String getError() {
        return error;
    }

    /**
     * Returns the numeric error code for external clients.
     *
     * @return numeric error code (0 = no error)
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the unmodifiable list of RAG source documents cited in the response.
     *
     * @return list of sources (never {@code null})
     */
    public List<Source> getSources() {
        return sources;
    }

    /**
     * Converts an internal {@link OpenRouterResponseDto} to its external public
     * representation.
     *
     * @param dto the internal DTO to convert
     * @return the external public DTO, or {@code null} if {@code dto} is
     *         {@code null}
     */
    public static OpenRouterPublicDto from(OpenRouterResponseDto dto) {
        if (dto == null)
            return null;
        int code = dto.getErrorCode() == null ? 0 : dto.getErrorCode().getCode();
        return new OpenRouterPublicDto(dto.isSuccess(), dto.getMessage(), dto.getError(), code, dto.getSources());
    }
}
