package cat.complai.auth;

import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link OidcIdentityTokenValidator}.
 *
 * The DI-managed constructor fetches live JWKS endpoints and is not exercised here.
 * Instead, the protected test constructor receives a city identifier, a pre-built
 * {@link JwtParser} backed by a locally-generated RSA key pair, and the NIF claim name,
 * so tests are fast, hermetic, and deterministic.
 */
class OidcIdentityTokenValidatorTest {

    private static final String ISSUER    = "https://test-issuer.example.com";
    private static final String AUDIENCE  = "test-client-id";
    private static final String TEST_CITY = "elprat";

    private static PrivateKey privateKey;
    private static OidcIdentityTokenValidator validator;

    @BeforeAll
    static void generateKeyPairAndValidator() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        privateKey = kp.getPrivate();
        PublicKey publicKey = kp.getPublic();

        JwtParser parser = Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build();

        // "sub" is the default nifClaim — matches what VALId typically puts the NIF in.
        validator = new OidcIdentityTokenValidator(TEST_CITY, parser, "sub");
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void validate_validToken_returnsVerifiedIdentity() {
        String token = buildToken(b -> b
                .subject("12345678A")
                .claim("given_name", "Joan")
                .claim("family_name", "Torres")
                .expiration(oneHourFromNow()));

        VerifiedCitizenIdentity identity = validator.validate(token, TEST_CITY);

        assertEquals("Joan", identity.name());
        assertEquals("Torres", identity.surname());
        assertEquals("12345678A", identity.nif());
    }

    @Test
    void validate_trimsWhitespaceInClaims() {
        String token = buildToken(b -> b
                .subject("  12345678A  ")
                .claim("given_name", "  Joan  ")
                .claim("family_name", " Torres ")
                .expiration(oneHourFromNow()));

        VerifiedCitizenIdentity identity = validator.validate(token, TEST_CITY);

        assertEquals("Joan", identity.name());
        assertEquals("Torres", identity.surname());
        assertEquals("12345678A", identity.nif());
    }

    // -------------------------------------------------------------------------
    // Expiry
    // -------------------------------------------------------------------------

    @Test
    void validate_expiredToken_throwsWithExpiredMessage() {
        String token = buildToken(b -> b
                .subject("12345678A")
                .claim("given_name", "Joan")
                .claim("family_name", "Torres")
                .expiration(Date.from(Instant.now().minus(1, ChronoUnit.HOURS))));

        IdentityTokenValidationException ex =
                assertThrows(IdentityTokenValidationException.class, () -> validator.validate(token, TEST_CITY));

        assertTrue(ex.getMessage().toLowerCase().contains("expired"),
                "Exception message should mention expiry, got: " + ex.getMessage());
    }

    @Test
    void validate_tokenWithoutExpiry_throwsWithExpiredMessage() {
        // Tokens without exp must be rejected — non-expiring tokens are a security risk.
        String token = buildToken(b -> b
                .subject("12345678A")
                .claim("given_name", "Joan")
                .claim("family_name", "Torres")
                // intentionally no .expiration(...)
        );

        IdentityTokenValidationException ex =
                assertThrows(IdentityTokenValidationException.class, () -> validator.validate(token, TEST_CITY));

        assertTrue(ex.getMessage().toLowerCase().contains("exp"),
                "Exception message should mention missing exp, got: " + ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // Wrong issuer / audience
    // -------------------------------------------------------------------------

    @Test
    void validate_wrongIssuer_throws() {
        // Build with a different issuer — parser requires ISSUER.
        String token = Jwts.builder()
                .issuer("https://evil-issuer.example.com")
                .audience().add(AUDIENCE).and()
                .subject("12345678A")
                .claim("given_name", "Joan")
                .claim("family_name", "Torres")
                .expiration(oneHourFromNow())
                .signWith(privateKey)
                .compact();

        assertThrows(IdentityTokenValidationException.class, () -> validator.validate(token, TEST_CITY));
    }

    @Test
    void validate_wrongAudience_throws() {
        String token = Jwts.builder()
                .issuer(ISSUER)
                .audience().add("other-client-id").and()
                .subject("12345678A")
                .claim("given_name", "Joan")
                .claim("family_name", "Torres")
                .expiration(oneHourFromNow())
                .signWith(privateKey)
                .compact();

        assertThrows(IdentityTokenValidationException.class, () -> validator.validate(token, TEST_CITY));
    }

    // -------------------------------------------------------------------------
    // Missing claims
    // -------------------------------------------------------------------------

    @Test
    void validate_missingGivenName_throws() {
        String token = buildToken(b -> b
                .subject("12345678A")
                // no given_name
                .claim("family_name", "Torres")
                .expiration(oneHourFromNow()));

        IdentityTokenValidationException ex =
                assertThrows(IdentityTokenValidationException.class, () -> validator.validate(token, TEST_CITY));
        assertTrue(ex.getMessage().contains("given_name"));
    }

    @Test
    void validate_missingFamilyName_throws() {
        String token = buildToken(b -> b
                .subject("12345678A")
                .claim("given_name", "Joan")
                // no family_name
                .expiration(oneHourFromNow()));

        IdentityTokenValidationException ex =
                assertThrows(IdentityTokenValidationException.class, () -> validator.validate(token, TEST_CITY));
        assertTrue(ex.getMessage().contains("family_name"));
    }

    @Test
    void validate_missingNifClaim_throws() {
        // "sub" is the nifClaim; omit it.
        String token = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                // no .subject(...)
                .claim("given_name", "Joan")
                .claim("family_name", "Torres")
                .expiration(oneHourFromNow())
                .signWith(privateKey)
                .compact();

        assertThrows(IdentityTokenValidationException.class, () -> validator.validate(token, TEST_CITY));
    }

    // -------------------------------------------------------------------------
    // Blank / null input
    // -------------------------------------------------------------------------

    @Test
    void validate_nullToken_throws() {
        assertThrows(IdentityTokenValidationException.class, () -> validator.validate(null, TEST_CITY));
    }

    @Test
    void validate_blankToken_throws() {
        assertThrows(IdentityTokenValidationException.class, () -> validator.validate("   ", TEST_CITY));
    }

    @Test
    void validate_unknownCity_throws() {
        // A city not present in the validator's config must be rejected explicitly.
        String token = buildToken(b -> b
                .subject("12345678A")
                .claim("given_name", "Joan")
                .claim("family_name", "Torres")
                .expiration(oneHourFromNow()));

        IdentityTokenValidationException ex =
                assertThrows(IdentityTokenValidationException.class,
                        () -> validator.validate(token, "unknowncity"));
        assertTrue(ex.getMessage().contains("unknowncity"),
                "Exception message should name the unknown city, got: " + ex.getMessage());
    }

    @Test
    void validate_blankCityId_throws() {
        String token = buildToken(b -> b
                .subject("12345678A")
                .claim("given_name", "Joan")
                .claim("family_name", "Torres")
                .expiration(oneHourFromNow()));

        assertThrows(IdentityTokenValidationException.class,
                () -> validator.validate(token, "  "));
    }

    // -------------------------------------------------------------------------
    // Custom nifClaim configuration
    // -------------------------------------------------------------------------

    @Test
    void validate_customNifClaim_extractsFromCorrectClaim() throws Exception {
        // Some IdPs put the NIF in a custom claim instead of "sub".
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        JwtParser customParser = Jwts.parser()
                .verifyWith(kp.getPublic())
                .requireIssuer(ISSUER)
                .requireAudience(AUDIENCE)
                .build();

        OidcIdentityTokenValidator customValidator =
                new OidcIdentityTokenValidator(TEST_CITY, customParser, "document_number");

        String token = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject("some-opaque-sub")
                .claim("given_name", "Joan")
                .claim("family_name", "Torres")
                .claim("document_number", "12345678A")
                .expiration(oneHourFromNow())
                .signWith(kp.getPrivate())
                .compact();

        VerifiedCitizenIdentity identity = customValidator.validate(token, TEST_CITY);

        assertEquals("12345678A", identity.nif());
        assertEquals("Joan", identity.name());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @FunctionalInterface
    private interface BuilderCustomiser {
        io.jsonwebtoken.JwtBuilder apply(io.jsonwebtoken.JwtBuilder b);
    }

    /**
     * Builds a signed JWT with the shared test key, issuer, and audience.
     * The caller supplies a lambda to configure payload claims.
     */
    private static String buildToken(BuilderCustomiser customiser) {
        io.jsonwebtoken.JwtBuilder base = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and();
        return customiser.apply(base)
                .signWith(privateKey)
                .compact();
    }

    private static Date oneHourFromNow() {
        return Date.from(Instant.now().plus(1, ChronoUnit.HOURS));
    }
}

