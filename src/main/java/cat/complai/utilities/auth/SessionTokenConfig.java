package cat.complai.utilities.auth;

import io.jsonwebtoken.security.Keys;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

import javax.crypto.SecretKey;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.logging.Logger;

/**
 * Configuration bean for session-token JWT settings.
 *
 * <p>Reuses the existing {@code jwt.secret} / {@code JWT_SECRET} environment
 * variable for HMAC-SHA256 signing. The client secret is stored as a SHA-256
 * hash — the plain-text secret never leaves the mobile app source code.
 *
 * <p>Fails fast at startup if any required value is missing or invalid.
 */
@Singleton
public class SessionTokenConfig {

    private static final Logger logger = Logger.getLogger(SessionTokenConfig.class.getName());

    private final SecretKey signingKey;
    private final String clientSecretHash;
    private final int tokenLifetimeSeconds;

    public SessionTokenConfig(
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${complai.session-token.client-secret-hash:}") String clientSecretHash,
            @Value("${complai.session-token.token-lifetime-seconds:900}") int tokenLifetimeSeconds) {

        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret is not configured. Set the JWT_SECRET environment variable "
                            + "to a base64-encoded HMAC-SHA256 key (>= 256 bits).");
        }

        byte[] keyBytes;
        try {
            keyBytes = java.util.Base64.getUrlDecoder().decode(jwtSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "jwt.secret is not valid base64url. Set JWT_SECRET to a base64 or base64url-encoded key.", e);
        }

        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "jwt.secret must encode at least 256 bits (32 bytes). Got " + keyBytes.length + " bytes.");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.clientSecretHash = clientSecretHash;
        this.tokenLifetimeSeconds = tokenLifetimeSeconds;

        logger.info(() -> "SessionTokenConfig initialised — tokenLifetimeSeconds=" + tokenLifetimeSeconds);
    }

    /**
     * Returns the HMAC signing key for JWT creation and validation.
     */
    public SecretKey getSigningKey() {
        return signingKey;
    }

    /**
     * Returns the token lifetime in seconds.
     */
    public int getTokenLifetimeSeconds() {
        return tokenLifetimeSeconds;
    }

    /**
     * Validates a plain-text client secret against the stored SHA-256 hash.
     * Uses constant-time comparison to prevent timing attacks.
     *
     * @param plainSecret the client secret sent by the mobile app
     * @return {@code true} if the hash matches
     */
    public boolean validateClientSecret(String plainSecret) {
        if (plainSecret == null || plainSecret.isBlank()) {
            return false;
        }
        if (clientSecretHash == null || clientSecretHash.isBlank()) {
            logger.warning("CLIENT_SECRET_HASH is not configured — rejecting all client secrets");
            return false;
        }

        String computedHash = sha256Hex(plainSecret);
        return MessageDigest.isEqual(
                clientSecretHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                computedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
