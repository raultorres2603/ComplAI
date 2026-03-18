package cat.complai.openrouter.dto;

import io.micronaut.core.annotation.Introspected;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Introspected
public class OpenRouterResponseDto {
    private final boolean success;
    private final String message; // AI message when success
    private final String error;   // Error message when not success
    private final Integer statusCode; // HTTP status code from AI service when available
    private final OpenRouterErrorCode errorCode;
    private final byte[] pdfData; // optional PDF bytes when a PDF was requested
    private final List<Source> sources; // list of sources with URL and title used as sources for the answer

    public OpenRouterResponseDto(boolean success, String message, String error, Integer statusCode, OpenRouterErrorCode errorCode) {
        this(success, message, error, statusCode, errorCode, null);
    }

    public OpenRouterResponseDto(boolean success, String message, String error, Integer statusCode, OpenRouterErrorCode errorCode, byte[] pdfData) {
        this(success, message, error, statusCode, errorCode, pdfData, List.of());
    }

    public OpenRouterResponseDto(boolean success, String message, String error, Integer statusCode, OpenRouterErrorCode errorCode, byte[] pdfData, List<Source> sources) {
        this.success = success;
        this.message = message;
        this.error = error;
        this.statusCode = statusCode;
        this.errorCode = errorCode == null ? OpenRouterErrorCode.NONE : errorCode;
        this.pdfData = pdfData;
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

    public Integer getStatusCode() {
        return statusCode;
    }

    public OpenRouterErrorCode getErrorCode() {
        return errorCode;
    }

    public byte[] getPdfData() {
        return pdfData;
    }

    public List<Source> getSources() {
        return sources;
    }
}
