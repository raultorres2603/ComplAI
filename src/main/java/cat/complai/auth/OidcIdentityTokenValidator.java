package cat.complai.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Key;
import java.time.Duration;
import java.util.Iterator;
import java.util.logging.Logger;

/**
 * Validates OIDC ID tokens passed in the {@code X-Identity-Token} header of
 * {@code POST /complai/redact} requests, then extracts the citizen's verified identity.
 *
 * <p>This bean only loads when {@code IDENTITY_VERIFICATION_ENABLED=true} is set in the
 * environment (maps to the {@code identity.verification.enabled} property via Micronaut
 * relaxed binding). When the flag is off the bean is absent and the controller's
 * {@code @Nullable} injection point receives {@code null} — the entire feature is
 * genuinely inactive with no code-path changes needed in callers.
 *
 * <p>The JWKS is fetched from {@code OIDC_JWKS_URI} at bean initialisation time.
 * If the endpoint is unreachable at startup, the application fails fast — a SAML/OIDC
 * feature that cannot load its IdP keys must not start silently degraded.
 *
 * <p>Key rotation: warm Lambda instances cache the JwkSet for their lifetime. A forced
 * cold start (re-deploy or Lambda recycle) will re-fetch the current keys. If the IdP
 * rotates keys without a deployment, existing warm instances will reject new tokens with
 * an unknown {@code kid}; a re-deploy resolves this. For a higher-availability rotation
 * strategy, upgrade to a periodic JWKS refresh in a future iteration.
 *
 * <p>This class never throws to callers. All failures are wrapped in
 * {@link IdentityTokenValidationException}.
 */
@Requires(property = "identity.verification.enabled", value = "true")
@Singleton
public class OidcIdentityTokenValidator {

    private static final Logger logger = Logger.getLogger(OidcIdentityTokenValidator.class.getName());
    private static final Duration JWKS_FETCH_TIMEOUT = Duration.ofSeconds(10);

    private final JwtParser parser;
    private final String nifClaim;

    @Inject
    public OidcIdentityTokenValidator(
            @Value("${oidc.issuer.url}") String issuerUrl,
            @Value("${oidc.jwks.uri}") String jwksUri,
            @Value("${oidc.audience}") String audience,
            @Value("${oidc.nif.claim:sub}") String nifClaim) {
        this(buildParser(issuerUrl, jwksUri, audience), nifClaim);
        logger.info("OidcIdentityTokenValidator initialised — issuer=" + issuerUrl
                + " jwksUri=" + jwksUri + " nifClaim=" + nifClaim);
    }

    /**
     * Protected constructor for unit tests. Bypasses JWKS fetch by accepting a
     * pre-built {@link JwtParser} and the claim name to use for the NIF/NIE.
     *
     * <p>Matches the pattern used by {@code HttpWrapper} — a protected no-arg/reduced
     * constructor lets tests subclass and override {@link #validate(String)} without
     * going through DI or hitting a live JWKS endpoint.
     */
    protected OidcIdentityTokenValidator(JwtParser parser, String nifClaim) {
        this.parser = parser;
        this.nifClaim = nifClaim;
    }

    /**
     * Validates the OIDC ID token and extracts the citizen's identity.
     *
     * @param token the raw JWT string from the {@code X-Identity-Token} header
     * @return a fully-populated {@link VerifiedCitizenIdentity} on success
     * @throws IdentityTokenValidationException on any validation failure — never throws other types
     */
    public VerifiedCitizenIdentity validate(String token) {
        if (token == null || token.isBlank()) {
            throw new IdentityTokenValidationException("X-Identity-Token value is blank");
        }

        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();

            // JJWT enforces exp when present; tokens without exp must be rejected explicitly.
            if (claims.getExpiration() == null) {
                throw new IdentityTokenValidationException("Identity token must carry an expiry (exp)");
            }

            String name    = claims.get("given_name", String.class);
            String surname = claims.get("family_name", String.class);
            String nif     = claims.get(nifClaim, String.class);

            if (isBlank(name) || isBlank(surname) || isBlank(nif)) {
                throw new IdentityTokenValidationException(
                        "Identity token is missing required claims: given_name, family_name, or "
                        + nifClaim);
            }

            return new VerifiedCitizenIdentity(name.trim(), surname.trim(), nif.trim());

        } catch (IdentityTokenValidationException e) {
            throw e;
        } catch (ExpiredJwtException e) {
            throw new IdentityTokenValidationException("Identity token has expired");
        } catch (JwtException e) {
            // Covers: invalid signature, wrong issuer/audience, malformed token, etc.
            // Not surfacing e.getMessage() at higher log levels to avoid leaking partial token material.
            logger.fine("Identity token rejected: " + e.getClass().getSimpleName());
            throw new IdentityTokenValidationException("Identity token is invalid");
        } catch (Exception e) {
            logger.warning("Unexpected error validating identity token: " + e.getClass().getSimpleName());
            throw new IdentityTokenValidationException("Identity token validation error");
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static JwtParser buildParser(String issuerUrl, String jwksUri, String audience) {
        JwkSet jwkSet = fetchAndParseJwks(jwksUri);
        return Jwts.parser()
                .keyLocator(header -> locateKey(jwkSet, (String) header.get("kid")))
                .requireIssuer(issuerUrl)
                .requireAudience(audience)
                .build();
    }

    /**
     * Fetches the JWKS JSON from the given URI and parses it.
     * Throws {@link IllegalStateException} on network error or parse failure — this is called
     * only from the constructor, so a failure here prevents the bean from being created, which
     * causes the application to fail fast at startup. That is the correct behaviour: a SAML
     * feature that cannot reach its IdP should not start in a degraded state.
     */
    private static JwkSet fetchAndParseJwks(String jwksUri) {
        String jwksJson;
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(JWKS_FETCH_TIMEOUT)
                .build()) {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(jwksUri))
                    .timeout(JWKS_FETCH_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "JWKS endpoint returned HTTP " + response.statusCode() + " from " + jwksUri);
            }
            jwksJson = response.body();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to fetch JWKS from " + jwksUri + ": " + e.getMessage(), e);
        }

        try {
            return Jwks.setParser().build().parse(jwksJson);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse JWKS from " + jwksUri, e);
        }
    }

    /**
     * Finds the signing key in the JwkSet.
     *
     * <p>When a {@code kid} header is present, it must match a key in the set.
     * When no {@code kid} is present (some IdPs omit it when there is only one key),
     * the first key in the set is used as a fallback.
     *
     * <p>Returns {@code null} if the set is empty — JJWT will then reject the token.
     */
    private static Key locateKey(JwkSet jwkSet, String kid) {
        if (kid != null) {
            for (Jwk<?> jwk : jwkSet) {
                if (kid.equals(jwk.getId())) {
                    return jwk.toKey();
                }
            }
            // kid present but not found — keys may have rotated since startup.
            // Log and fall through: returning null causes JJWT to throw, which maps to a
            // descriptive IdentityTokenValidationException in the caller.
            logger.warning("No key with kid=" + kid + " found in cached JWKS. "
                    + "If the IdP recently rotated keys, a Lambda restart will re-fetch them.");
            return null;
        }

        // No kid — use the first available key (single-key IdPs omit kid by convention).
        Iterator<Jwk<?>> it = jwkSet.iterator();
        return it.hasNext() ? it.next().toKey() : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

