package cat.complai.http.dto;

/**
 * Holds the raw response data returned by an OpenRouter HTTP call.
 *
 * @param message    the response body text on success
 * @param statusCode the HTTP status code, or {@code null} if unavailable
 * @param method     the HTTP method used (e.g. "POST")
 * @param error      a description of the error on failure, or {@code null} on
 *                   success
 */
public record HttpDto(String message, Integer statusCode, String method, String error) {
}
