package cat.complai.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

/**
 * Offline CLI utility to mint HS256 JWT tokens for use with the ComplAI API.
 *
 * This class is NOT a deployed endpoint. It is run locally by operators who need to
 * generate tokens for API consumers. The JWT_SECRET environment variable must be set
 * to the same Base64-encoded secret that the deployed Lambda uses.
 *
 * Usage:
 *   java -cp complai-all.jar cat.complai.auth.TokenGenerator <subject> <expiry-days>
 *
 * Example:
 *   JWT_SECRET=$(openssl rand -base64 32) \
 *   java -cp build/libs/complai-all.jar cat.complai.auth.TokenGenerator citizen-app 30
 *
 * The generated token must be passed as:
 *   Authorization: Bearer <token>
 */
public class TokenGenerator {

    private static final String ISSUER = "complai";
    private static final int MIN_SECRET_BYTES = 32;

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: TokenGenerator <subject> <expiry-days>");
            System.err.println("  subject      — consumer identifier (e.g. citizen-app)");
            System.err.println("  expiry-days  — positive integer, e.g. 30");
            System.exit(1);
        }

        String subject = args[0].trim();
        int expiryDays;
        try {
            expiryDays = Integer.parseInt(args[1].trim());
            if (expiryDays <= 0) throw new NumberFormatException("must be positive");
        } catch (NumberFormatException e) {
            System.err.println("expiry-days must be a positive integer");
            System.exit(1);
            return;
        }

        String rawSecret = System.getenv("JWT_SECRET");
        if (rawSecret == null || rawSecret.isBlank()) {
            System.err.println("JWT_SECRET environment variable is not set");
            System.exit(1);
            return;
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(rawSecret.trim());
        } catch (IllegalArgumentException e) {
            System.err.println("JWT_SECRET is not valid Base64");
            System.exit(1);
            return;
        }

        if (keyBytes.length < MIN_SECRET_BYTES) {
            System.err.printf("JWT_SECRET encodes only %d bytes; minimum is %d (256 bits)%n",
                    keyBytes.length, MIN_SECRET_BYTES);
            System.exit(1);
            return;
        }

        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        Instant now = Instant.now();
        Instant expiry = now.plus(expiryDays, ChronoUnit.DAYS);

        String token = Jwts.builder()
                .subject(subject)
                .issuer(ISSUER)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();

        System.out.println(token);
        System.err.printf("Token for subject='%s', expires in %d days (%s)%n",
                subject, expiryDays, expiry);
    }
}

