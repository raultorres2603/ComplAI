package cat.complai.utilities.auth;

import cat.complai.exceptions.IdentityTokenValidationException;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * Null Object implementation of {@link OidcIdentityTokenValidator} that is
 * activated when {@code api.key.enabled} is <em>not</em> set (e.g. on the SQS
 * worker Lambda where no HTTP requests are handled and OIDC is irrelevant).
 *
 * <p>In this context, identity verification is always disabled:
 * {@link #isEnabledForCity(String)} always returns {@code false}, so the
 * controller never attempts OIDC validation. This eliminates the need for
 * {@code @Nullable} injections and null checks throughout the codebase.
 *
 * <p>When {@code api.key.enabled} is set (the API Lambda), the real
 * {@link OidcIdentityTokenValidator} bean (annotated with
 * {@code @Requires(property = "api.key.enabled")}) takes precedence and this
 * class is not loaded.
 */
@Requires(missingProperty = "api.key.enabled")
@Singleton
public class DisabledIdentityTokenValidator extends OidcIdentityTokenValidator {

    public DisabledIdentityTokenValidator() {
        super();
    }

    /**
     * Always returns {@code false} — OIDC identity verification is not available
     * in this context (no JWKS fetches, no token validation).
     */
    @Override
    public boolean isEnabledForCity(String cityId) {
        return false;
    }

    /**
     * Never called in practice (the controller only invokes this when
     * {@link #isEnabledForCity(String)} returns {@code true}), but provided for
     * completeness.
     */
    @Override
    public VerifiedCitizenIdentity validate(String token, String cityId) {
        throw new UnsupportedOperationException(
                "OIDC validation is disabled — api.key.enabled is not set");
    }
}
