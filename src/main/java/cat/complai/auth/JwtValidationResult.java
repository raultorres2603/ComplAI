package cat.complai.auth;

/**
 * Typed result of a JWT validation attempt.
 *
 * On success: valid=true, subject holds the token's "sub" claim, failureReason is null.
 * On failure: valid=false, subject is null, failureReason carries a human-readable explanation
 *             (never the secret — only claim names and error categories).
 */
public record JwtValidationResult(boolean valid, String subject, String failureReason) {

    public static JwtValidationResult success(String subject) {
        return new JwtValidationResult(true, subject, null);
    }

    public static JwtValidationResult failure(String reason) {
        return new JwtValidationResult(false, null, reason);
    }
}

