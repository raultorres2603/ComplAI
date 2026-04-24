package cat.complai.dto.openrouter;

import io.micronaut.core.annotation.Introspected;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Internal response DTO produced by
 * {@link cat.complai.services.openrouter.IOpenRouterService}.
 *
 * <p>
 * Used exclusively within the service and controller layers. External clients
 * receive
 * {@link OpenRouterPublicDto} which strips the raw {@code pdfData} bytes and
 * normalises
 * the error code to a plain integer.
 */
@Introspected
public class OpenRouterResponseDto {
    private final boolean success;
    private final String message; // AI message when success
    private final String error; // Error message when not success
    private final Integer statusCode; // HTTP status code from AI service when available
    private final OpenRouterErrorCode errorCode;
    private final byte[] pdfData; // optional PDF bytes when a PDF was requested
    private final List<Source> sources; // list of sources with URL and title used as sources for the answer

    /**
     * Constructs a response without PDF data or sources.
     *
     * @param success    {@code true} if the request succeeded
     * @param message    the AI-generated text on success, or {@code null} on
     *                   failure
     * @param error      the error description on failure, or {@code null} on
     *                   success
     * @param statusCode the upstream HTTP status code, or {@code null} if
     *                   unavailable
     * @param errorCode  the typed error classification
     */
    public OpenRouterResponseDto(boolean success, String message, String error, Integer statusCode,
            OpenRouterErrorCode errorCode) {
        this(success, message, error, statusCode, errorCode, null);
    }

    /**
     * Constructs a response with optional PDF data but no sources.
     *
     * @param success    {@code true} if the request succeeded
     * @param message    the AI-generated text on success
     * @param error      the error description on failure
     * @param statusCode the upstream HTTP status code
     * @param errorCode  the typed error classification
     * @param pdfData    PDF bytes when a PDF was generated, or {@code null}
     */
    public OpenRouterResponseDto(boolean success, String message, String error, Integer statusCode,
            OpenRouterErrorCode errorCode, byte[] pdfData) {
        this(success, message, error, statusCode, errorCode, pdfData, List.of());
    }

    /**
     * Constructs a fully-populated response.
     *
     * @param success    {@code true} if the request succeeded
     * @param message    the AI-generated text on success
     * @param error      the error description on failure
     * @param statusCode the upstream HTTP status code
     * @param errorCode  the typed error classification; defaults to
     *                   {@link OpenRouterErrorCode#NONE} if {@code null}
     * @param pdfData    PDF bytes when a PDF was generated, or {@code null}
     * @param sources    list of RAG sources cited in the response
     */
    public OpenRouterResponseDto(boolean success, String message, String error, Integer statusCode,
            OpenRouterErrorCode errorCode, byte[] pdfData, List<Source> sources) {
        this.success = success;
        this.message = message;
        this.error = error;
        this.statusCode = statusCode;
        this.errorCode = errorCode == null ? OpenRouterErrorCode.NONE : errorCode;
        this.pdfData = pdfData;
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
     * Returns the upstream HTTP status code, or {@code null} if unavailable.
     *
     * @return upstream status code
     */
    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the typed error classification.
     *
     * @return error code
     */
    public OpenRouterErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the PDF bytes when a PDF response was generated, or {@code null}
     * otherwise.
     *
     * @return PDF bytes, or {@code null}
     */
    public byte[] getPdfData() {
        return pdfData;
    }

    /**
     * Returns the unmodifiable list of RAG source documents cited in the response.
     *
     * @return list of sources (never {@code null})
     */
    public List<Source> getSources() {
        return sources;
    }
}
