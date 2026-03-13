package cat.complai.auth;

/**
 * Typed result of a JWT validation attempt.
 *
 * On success: valid=true, subject holds the token's "sub" claim, city holds the "city" claim
 *             (required — tokens without a city claim are rejected), failureReason is null.
 * On failure: valid=false, subject and city are null, failureReason carries a human-readable
 *             explanation (never the secret — only claim names and error categories).
 */
public record JwtValidationResult(boolean valid, String subject, String city, String failureReason) {

    public static JwtValidationResult success(String subject, String city) {
        return new JwtValidationResult(true, subject, city, null);
    }

    public static JwtValidationResult failure(String reason) {
        return new JwtValidationResult(false, null, null, reason);
    }
}
