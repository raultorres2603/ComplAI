package cat.complai.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtValidatorTest {

    // 32-byte key — satisfies the 256-bit minimum required by HS256.
    private static final String VALID_SECRET_B64 = "hEmatrRKbxfC/9PxZ14VsYksRkTZHMpqRScBUhshYzQ=";
    private static final String ISSUER = "complai";

    private SecretKey key;
    private JwtValidator validator;

    @BeforeEach
    void setUp() {
        byte[] keyBytes = Base64.getDecoder().decode(VALID_SECRET_B64);
        key = Keys.hmacShaKeyFor(keyBytes);
        // Use the package-visible constructor so we don't need the Micronaut context.
        validator = new JwtValidator(key);
    }

    // --- Happy path ---

    @Test
    void validToken_returnsSuccessWithSubject() {
        String token = buildToken("citizen-app", ISSUER, futureDate(30));

        JwtValidationResult result = validator.validate("Bearer " + token);

        assertTrue(result.valid());
        assertEquals("citizen-app", result.subject());
        assertNull(result.failureReason());
    }

    // --- Missing / malformed header ---

    @Test
    void nullHeader_returnsFailure() {
        JwtValidationResult result = validator.validate(null);
        assertFalse(result.valid());
        assertNotNull(result.failureReason());
    }

    @Test
    void blankHeader_returnsFailure() {
        JwtValidationResult result = validator.validate("   ");
        assertFalse(result.valid());
        assertNotNull(result.failureReason());
    }

    @Test
    void headerWithoutBearerPrefix_returnsFailure() {
        String token = buildToken("citizen-app", ISSUER, futureDate(30));
        JwtValidationResult result = validator.validate(token); // no "Bearer " prefix

        assertFalse(result.valid());
        assertTrue(result.failureReason().toLowerCase().contains("bearer"));
    }

    @Test
    void bearerKeywordWithEmptyToken_returnsFailure() {
        JwtValidationResult result = validator.validate("Bearer ");
        assertFalse(result.valid());
    }

    // --- Signature ---

    @Test
    void tokenSignedWithDifferentKey_returnsFailure() {
        byte[] otherKeyBytes = Base64.getDecoder().decode("dGVzdC1zZWNyZXQtdGhhdC1pcy1leGFjdGx5LTMyLWJ5dGVzLWxvbmcx");
        SecretKey otherKey = Keys.hmacShaKeyFor(otherKeyBytes);
        String token = Jwts.builder()
                .subject("citizen-app")
                .issuer(ISSUER)
                .issuedAt(new Date())
                .expiration(futureDate(30))
                .signWith(otherKey)
                .compact();

        JwtValidationResult result = validator.validate("Bearer " + token);

        assertFalse(result.valid());
    }

    // --- Expiry ---

    @Test
    void expiredToken_returnsFailureWithExpiredReason() {
        // exp set 1 day in the past
        Date past = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
        String token = buildToken("citizen-app", ISSUER, past);

        JwtValidationResult result = validator.validate("Bearer " + token);

        assertFalse(result.valid());
        assertTrue(result.failureReason().toLowerCase().contains("expired"));
    }

    @Test
    void tokenWithoutExpClaim_returnsFailure() {
        String token = Jwts.builder()
                .subject("citizen-app")
                .issuer(ISSUER)
                .issuedAt(new Date())
                // deliberately omitting .expiration(...)
                .signWith(key)
                .compact();

        JwtValidationResult result = validator.validate("Bearer " + token);

        assertFalse(result.valid());
        assertTrue(result.failureReason().toLowerCase().contains("exp"));
    }

    // --- Issuer ---

    @Test
    void tokenWithWrongIssuer_returnsFailure() {
        String token = buildToken("citizen-app", "some-other-system", futureDate(30));

        JwtValidationResult result = validator.validate("Bearer " + token);

        assertFalse(result.valid());
    }

    // --- Subject ---

    @Test
    void tokenWithoutSubjectClaim_returnsFailure() {
        String token = Jwts.builder()
                .issuer(ISSUER)
                .issuedAt(new Date())
                .expiration(futureDate(30))
                .signWith(key)
                .compact();

        JwtValidationResult result = validator.validate("Bearer " + token);

        assertFalse(result.valid());
        assertTrue(result.failureReason().toLowerCase().contains("sub"));
    }

    // --- Startup guard ---

    @Test
    void constructorWithNullSecret_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () -> new JwtValidator((String) null));
    }

    @Test
    void constructorWithBlankSecret_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () -> new JwtValidator(""));
    }

    @Test
    void constructorWithInvalidBase64_throwsIllegalState() {
        assertThrows(IllegalStateException.class, () -> new JwtValidator("not-valid-base64!!!"));
    }

    @Test
    void constructorWithKeyBelowMinimumLength_throwsIllegalState() {
        // 16 bytes = 128 bits — below the 256-bit minimum
        String shortSecret = Base64.getEncoder().encodeToString("only-sixteen-byt".getBytes());
        assertThrows(IllegalStateException.class, () -> new JwtValidator(shortSecret));
    }


    private String buildToken(String subject, String issuer, Date expiry) {
        return Jwts.builder()
                .subject(subject)
                .issuer(issuer)
                .issuedAt(new Date())
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    private Date futureDate(int days) {
        return Date.from(Instant.now().plus(days, ChronoUnit.DAYS));
    }
}

