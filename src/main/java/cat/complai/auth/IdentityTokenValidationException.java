package cat.complai.auth;
/**
 * Thrown by {@link OidcIdentityTokenValidator} when the {@code X-Identity-Token} header
 * value cannot be validated.
 *
 * <p>The message describes the failure category (expired, invalid signature, missing claim, etc.)
 * but never includes the raw token value or any PII.
 *
 * <p>Callers should map this to a {@code 401 Unauthorized} response.
 */
public class IdentityTokenValidationException extends RuntimeException {
    public IdentityTokenValidationException(String message) {
        super(message);
    }
    public IdentityTokenValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
