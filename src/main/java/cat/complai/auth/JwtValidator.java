package cat.complai.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * Validates HS256 JWT Bearer tokens.
 *
 * The secret is injected via the {@code jwt.secret} Micronaut property.
 * Micronaut's relaxed binding maps the {@code JWT_SECRET} environment variable to {@code jwt.secret}
 * automatically, so no nested placeholder expression is needed.
 * If the property is absent or encodes fewer than 256 bits, the application fails to start
 * immediately — there is no silent degradation to an insecure state.
 *
 * This class never throws to callers. All JJWT exceptions are caught and mapped to a
 * descriptive failureReason in the returned JwtValidationResult.
 *
 * The secret value is never logged.
 */
// Only instantiate this bean when jwt.secret is configured.  The API Lambda supplies
// JWT_SECRET; the SQS worker Lambda does not — it never handles HTTP requests and has
// no need for JWT validation.  Without this guard Micronaut eagerly initialises the bean
// in both Lambdas and the worker crashes at startup with a missing-property error.
@Requires(property = "jwt.secret")
@Singleton
public class JwtValidator {

    private static final Logger logger = Logger.getLogger(JwtValidator.class.getName());
    private static final String EXPECTED_ISSUER = "complai";
    // HMAC-SHA256 requires a key of at least 256 bits (32 bytes).
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;

    @Inject
    public JwtValidator(@Value("${jwt.secret}") String base64Secret) {
        this(base64Secret, true);
        logger.info("JwtValidator initialised — HS256 signing key loaded");
    }

    // Package-visible constructor for unit tests that supply a pre-built key directly,
    // bypassing the @Value injection path.
    JwtValidator(SecretKey signingKey) {
        this.signingKey = signingKey;
    }


    // Shared validation + key construction logic.
    private JwtValidator(String base64Secret, boolean ignored) {
        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET property is required but not set. " +
                    "Generate one with: openssl rand -base64 32");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(base64Secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "JWT_SECRET is not valid Base64. " +
                    "Generate a valid secret with: openssl rand -base64 32");
        }

        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(String.format(
                    "JWT_SECRET encodes only %d bytes; minimum is %d (256 bits). " +
                    "Generate a stronger secret with: openssl rand -base64 32",
                    keyBytes.length, MIN_SECRET_BYTES));
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Validates the raw value of the Authorization header (expected: "Bearer <token>").
     *
     * Returns a success result with the token's subject on success.
     * Returns a failure result with a descriptive reason on any validation error.
     * Never throws.
     */
    public JwtValidationResult validate(String authorizationHeaderValue) {
        if (authorizationHeaderValue == null || authorizationHeaderValue.isBlank()) {
            return JwtValidationResult.failure("Missing Authorization header");
        }

        if (!authorizationHeaderValue.startsWith("Bearer ")) {
            return JwtValidationResult.failure("Authorization header must use the Bearer scheme");
        }

        String token = authorizationHeaderValue.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            return JwtValidationResult.failure("Bearer token is empty");
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(EXPECTED_ISSUER)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // exp is enforced by JJWT automatically when present; rejecting tokens that omit
            // the claim entirely requires an explicit null-check because JJWT only validates
            // exp if the claim exists.
            if (claims.getExpiration() == null) {
                return JwtValidationResult.failure("Token must have an expiry claim (exp)");
            }

            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                return JwtValidationResult.failure("Token is missing the subject claim (sub)");
            }

            String city = claims.get("city", String.class);
            if (city == null || city.isBlank()) {
                return JwtValidationResult.failure("Token is missing the city claim");
            }

            return JwtValidationResult.success(subject, city);

        } catch (ExpiredJwtException e) {
            return JwtValidationResult.failure("Token has expired");
        } catch (JwtException e) {
            // Covers: invalid signature, malformed token, wrong issuer, etc.
            // Not logging e.getMessage() at higher levels to avoid leaking partial token material.
            logger.fine("JWT validation rejected: " + e.getClass().getSimpleName());
            return JwtValidationResult.failure("Token is invalid: " + e.getMessage());
        } catch (Exception e) {
            logger.warning("Unexpected error during JWT validation: " + e.getClass().getSimpleName());
            return JwtValidationResult.failure("Token validation error");
        }
    }
}
