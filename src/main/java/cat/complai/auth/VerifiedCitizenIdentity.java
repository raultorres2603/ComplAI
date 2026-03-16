package cat.complai.auth;

/**
 * Citizen identity attributes extracted from a validated OIDC ID token.
 *
 * All three fields are guaranteed non-null and non-blank when this record is returned
 * by {@link OidcIdentityTokenValidator} — the validator throws
 * {@link IdentityTokenValidationException} before returning partial data.
 *
 * This record is intentionally separate from {@link cat.complai.openrouter.dto.ComplainantIdentity}.
 * ComplainantIdentity lives at the HTTP boundary and may contain self-reported, unverified data.
 * VerifiedCitizenIdentity signals that the data was extracted from a cryptographically
 * validated IdP token — the distinction matters for auditing and future compliance requirements.
 */
public record VerifiedCitizenIdentity(String name, String surname, String nif) {}

