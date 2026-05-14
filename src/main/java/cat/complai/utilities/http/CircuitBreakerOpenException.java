package cat.complai.utilities.http;

/**
 * Thrown when a call is attempted while the {@link CircuitBreaker} is in the
 * {@code OPEN} state and the cooldown period has not yet elapsed.
 *
 * <p>
 * This exception signals that the caller should fail fast rather than
 * attempting the underlying HTTP call, which would be futile against a
 * degraded upstream provider.
 * </p>
 */
public class CircuitBreakerOpenException extends RuntimeException {

    private static final String DEFAULT_MESSAGE =
            "El servei d'intel·ligència artificial no està disponible en aquests moments. Torneu-ho a intentar d'aquí a uns minuts.";

    /**
     * Constructs the exception with the default friendly Catalan fallback message.
     */
    public CircuitBreakerOpenException() {
        super(DEFAULT_MESSAGE);
    }

    /**
     * Constructs the exception with a custom message.
     *
     * @param message human-readable description
     */
    public CircuitBreakerOpenException(String message) {
        super(message != null ? message : DEFAULT_MESSAGE);
    }

    /**
     * Constructs the exception with a custom message and cause.
     *
     * @param message human-readable description
     * @param cause   the underlying exception that led to the circuit opening
     */
    public CircuitBreakerOpenException(String message, Throwable cause) {
        super(message != null ? message : DEFAULT_MESSAGE, cause);
    }
}