package cat.complai.utilities.auth;

/**
 * Shared constants for request attributes set by authentication filters.
 *
 * <p>Both {@link JwtSessionAuthFilter} (production) and the test replacement
 * filter set these attributes on the request so that downstream controllers,
 * logging filters, and rate limiters can read them without depending on the
 * specific filter implementation.
 */
public final class AuthConstants {

    private AuthConstants() {
        // Utility class — no instantiation
    }

    /**
     * Request attribute key for the city identifier extracted from the JWT
     * {@code city} claim (or resolved by the auth filter).
     */
    public static final String CITY_ATTRIBUTE = "city";

    /**
     * Request attribute key for the authenticated user/client identifier.
     * Set to {@code "jwt-session"} by the production filter, or
     * {@code "anonymous"} when no identity is available.
     */
    public static final String USER_ATTRIBUTE = "user";
}
