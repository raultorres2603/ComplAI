package cat.complai.auth;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OIDC configuration for a single city, deserialized from {@code oidc-mapping.json}.
 *
 * <p>The JSON uses snake_case keys; {@code @JsonProperty} maps them to camelCase fields.
 * One entry per city — the JSON object key is the city identifier (e.g. {@code "elprat"}).
 */
public record OidcConfig(
        String issuer,
        @JsonProperty("jwks_uri") String jwksUri,
        String audience,
        @JsonProperty("nif_claim") String nifClaim) {
}

