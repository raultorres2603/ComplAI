package cat.complai.exceptions.ses;

/**
 * Exception thrown when an error occurs during SES (Simple Email Service) email
 * sending operations.
 * This exception wraps failures related to sending emails through AWS SES,
 * including
 * authentication failures, malformed messages, and service-level errors.
 *
 * @author ComplAI Team
 */
public class SesEmailException extends RuntimeException {

    /**
     * Constructs a new SesEmailException with the specified detail message.
     *
     * @param message the detail message explaining the SES email failure
     */
    public SesEmailException(String message) {
        super(message);
    }

    /**
     * Constructs a new SesEmailException with the specified detail message and
     * cause.
     *
     * @param message the detail message explaining the SES email failure
     * @param cause   the underlying cause of this exception
     */
    public SesEmailException(String message, Throwable cause) {
        super(message, cause);
    }
}
