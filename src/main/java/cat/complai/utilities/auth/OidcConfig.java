package cat.complai.utilities.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;

/**
 * OIDC configuration for a single city, deserialized from {@code oidc-mapping.json}.
 *
 * <p>The JSON uses snake_case keys; {@code @JsonProperty} maps them to camelCase fields.
 * One entry per city — the JSON object key is the city identifier (e.g. {@code "elprat"}).
 *
 * <p>{@code enabled} controls whether OIDC identity verification is active for this city.
 * When {@code false} the entry is ignored at startup: no JWKS fetch is performed and
 * {@link OidcIdentityTokenValidator#isEnabledForCity(String)} returns {@code false}.
 *
 * <p>Annotated with {@code @Introspected} so that Micronaut generates compile-time
 * {@link io.micronaut.core.beans.BeanIntrospection} metadata. This is required for
 * Jackson deserialization in GraalVM native images — without it the canonical record
 * constructor is invisible at runtime and native-image fails with:
 * {@code "Cannot construct instance ... no delegate- or property-based Creator"}.
 */
@Introspected
public record OidcConfig(
        boolean enabled,
        String issuer,
        @JsonProperty("jwks_uri") String jwksUri,
        String audience,
        @JsonProperty("nif_claim") String nifClaim) {
}

