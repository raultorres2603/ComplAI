package cat.complai.dto.http;

import cat.complai.dto.openrouter.OpenRouterErrorCode;

/**
 * Holds the raw response data returned by an OpenRouter HTTP call.
 *
 * @param message    the response body text on success
 * @param statusCode the HTTP status code, or {@code null} if unavailable
 * @param method     the HTTP method used (e.g. "POST")
 * @param error      a description of the error on failure, or {@code null} on
 *                   success
 * @param errorCode  the typed {@link OpenRouterErrorCode} classifying the failure,
 *                   or {@code null} when there is no error or the status code is sufficient
 */
public record HttpDto(String message, Integer statusCode, String method, String error,
                       OpenRouterErrorCode errorCode) {

    /**
     * Constructs an {@code HttpDto} with no error code — suitable for successful responses.
     */
    public HttpDto(String message, Integer statusCode, String method, String error) {
        this(message, statusCode, method, error, null);
    }

    /**
     * Returns whether this response represents a successful HTTP call.
     *
     * @return {@code true} when statusCode is 200
     */
    public boolean isSuccess() {
        return statusCode != null && statusCode == 200;
    }
}
