package cat.complai.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.Key;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Validates OIDC ID tokens passed in the {@code X-Identity-Token} header of
 * {@code POST /complai/redact} requests, then extracts the citizen's verified identity.
 *
 * <p>OIDC configuration (issuer, JWKS URI, audience, NIF claim) is loaded per city from
 * {@code oidc-mapping.json} on the classpath. The city identifier is taken from the
 * already-validated ComplAI Bearer JWT ({@code city} claim), which {@link JwtAuthFilter}
 * sets as a request attribute before the controller is reached. This keeps the OIDC config
 * lookup independent of the unverified OIDC token itself.
 *
 * <p>This bean only loads when {@code IDENTITY_VERIFICATION_ENABLED=true} is set in the
 * environment (maps to {@code identity.verification.enabled} via Micronaut relaxed binding).
 * When the flag is off the bean is absent and the controller's {@code @Nullable} injection
 * point receives {@code null} — the entire feature is inactive with no code-path changes.
 *
 * <p>JWKS endpoints for all configured cities are fetched at bean initialisation time.
 * If any endpoint is unreachable the application fails fast — a partially-configured
 * multi-city setup must not start in a silently degraded state.
 *
 * <p>Key rotation: warm Lambda instances cache each city's JwkSet for their lifetime. A
 * forced cold start (re-deploy or Lambda recycle) re-fetches all current keys. If an IdP
 * rotates keys without a deployment, existing warm instances will reject new tokens with an
 * unknown {@code kid}; a re-deploy resolves this.
 *
 * <p>All failures are wrapped in {@link IdentityTokenValidationException}.
 */
@Requires(property = "identity.verification.enabled", value = "true")
@Singleton
public class OidcIdentityTokenValidator {

    private static final Logger logger = Logger.getLogger(OidcIdentityTokenValidator.class.getName());
    private static final Duration JWKS_FETCH_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Holds the per-city validation state: a ready-to-use {@link JwtParser} (already
     * configured with signing keys, required issuer, and audience) plus the claim name
     * that contains the citizen's NIF/NIE for this city's IdP.
     */
    private record CityValidationContext(JwtParser parser, String nifClaim) {}

    private final Map<String, CityValidationContext> cityContexts;

    @Inject
    public OidcIdentityTokenValidator() {
        Map<String, OidcConfig> mapping = loadMapping();
        Map<String, CityValidationContext> contexts = new HashMap<>(mapping.size());
        for (Map.Entry<String, OidcConfig> entry : mapping.entrySet()) {
            String cityId = entry.getKey();
            OidcConfig config = entry.getValue();
            // fetchAndParseJwks throws IllegalStateException if the endpoint is unreachable,
            // which propagates out of the constructor and prevents the bean from being created.
            JwkSet jwkSet = fetchAndParseJwks(config.jwksUri());
            JwtParser parser = buildParser(config, jwkSet);
            contexts.put(cityId, new CityValidationContext(parser, config.nifClaim()));
            logger.info("OidcIdentityTokenValidator initialised for city=" + cityId
                    + " issuer=" + config.issuer() + " nifClaim=" + config.nifClaim());
        }
        this.cityContexts = Map.copyOf(contexts);
    }

    /**
     * Protected constructor for unit tests. Bypasses classpath loading and JWKS fetch by
     * accepting a pre-built {@link JwtParser} wired to a specific test city identifier.
     *
     * @param testCityId the city identifier tests will pass to {@link #validate(String, String)}
     * @param parser     a pre-built parser backed by a locally-generated key pair
     * @param nifClaim   the JWT claim name to read the NIF/NIE from
     */
    protected OidcIdentityTokenValidator(String testCityId, JwtParser parser, String nifClaim) {
        this.cityContexts = Map.of(testCityId, new CityValidationContext(parser, nifClaim));
    }

    /**
     * Validates the OIDC ID token using the OIDC configuration for {@code cityId} and
     * extracts the citizen's verified identity.
     *
     * <p>The {@code cityId} must be the value from the validated ComplAI Bearer JWT
     * ({@code city} claim) — not from the OIDC token itself.
     *
     * @param token  the raw JWT string from the {@code X-Identity-Token} header
     * @param cityId the city from the validated ComplAI Bearer JWT {@code city} claim
     * @return a fully-populated {@link VerifiedCitizenIdentity} on success
     * @throws IdentityTokenValidationException on any validation failure
     */
    public VerifiedCitizenIdentity validate(String token, String cityId) {
        if (token == null || token.isBlank()) {
            throw new IdentityTokenValidationException("X-Identity-Token value is blank");
        }
        if (cityId == null || cityId.isBlank()) {
            throw new IdentityTokenValidationException(
                    "City identifier is blank — cannot select OIDC config");
        }

        CityValidationContext ctx = cityContexts.get(cityId);
        if (ctx == null) {
            throw new IdentityTokenValidationException(
                    "OIDC is not configured for city: " + cityId
                            + ". Add an entry to oidc-mapping.json.");
        }

        try {
            Claims claims = ctx.parser().parseSignedClaims(token).getPayload();

            // JJWT enforces exp when present; tokens without exp must be rejected explicitly.
            if (claims.getExpiration() == null) {
                throw new IdentityTokenValidationException("Identity token must carry an expiry (exp)");
            }

            String name    = claims.get("given_name", String.class);
            String surname = claims.get("family_name", String.class);
            String nif     = claims.get(ctx.nifClaim(), String.class);

            if (isBlank(name) || isBlank(surname) || isBlank(nif)) {
                throw new IdentityTokenValidationException(
                        "Identity token is missing required claims: given_name, family_name, or "
                        + ctx.nifClaim());
            }

            return new VerifiedCitizenIdentity(name.trim(), surname.trim(), nif.trim());

        } catch (IdentityTokenValidationException e) {
            throw e;
        } catch (ExpiredJwtException e) {
            throw new IdentityTokenValidationException("Identity token has expired");
        } catch (JwtException e) {
            // Covers: invalid signature, wrong issuer/audience, malformed token, etc.
            // Not surfacing e.getMessage() at higher log levels to avoid leaking token material.
            logger.fine("Identity token rejected for city=" + cityId
                    + ": " + e.getClass().getSimpleName());
            throw new IdentityTokenValidationException("Identity token is invalid");
        } catch (Exception e) {
            logger.warning("Unexpected error validating identity token for city=" + cityId
                    + ": " + e.getClass().getSimpleName());
            throw new IdentityTokenValidationException("Identity token validation error");
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static Map<String, OidcConfig> loadMapping() {
        try (InputStream is = OidcIdentityTokenValidator.class.getClassLoader()
                .getResourceAsStream("oidc/oidc-mapping.json")) {
            if (is == null) {
                throw new IllegalStateException("oidc-mapping.json not found on classpath");
            }
            return new ObjectMapper().readValue(is, new TypeReference<>() {});
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load oidc-mapping.json", e);
        }
    }

    private static JwtParser buildParser(OidcConfig config, JwkSet jwkSet) {
        return Jwts.parser()
                .keyLocator(header -> locateKey(jwkSet, (String) header.get("kid")))
                .requireIssuer(config.issuer())
                .requireAudience(config.audience())
                .build();
    }

    /**
     * Fetches the JWKS JSON from the given URI and parses it into a {@link JwkSet}.
     *
     * <p>Called only from the constructor. A failure here prevents the bean from being
     * created, so the application fails fast. That is the correct behaviour: a multi-city
     * OIDC setup that cannot reach one of its IdPs must not start in a degraded state.
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

