package cat.complai.exceptions.ses;

/**
 * Exception thrown when an error occurs during CloudWatch Logs filtering or
 * query operations.
 * This exception wraps failures related to querying, filtering, or retrieving
 * logs from AWS CloudWatch Logs,
 * including authentication failures, invalid queries, and service-level errors.
 *
 * @author ComplAI Team
 */
public class CloudWatchLogsException extends RuntimeException {

    /**
     * Constructs a new CloudWatchLogsException with the specified detail message.
     *
     * @param message the detail message explaining the CloudWatch Logs query
     *                failure
     */
    public CloudWatchLogsException(String message) {
        super(message);
    }

    /**
     * Constructs a new CloudWatchLogsException with the specified detail message
     * and cause.
     *
     * @param message the detail message explaining the CloudWatch Logs query
     *                failure
     * @param cause   the underlying cause of this exception
     */
    public CloudWatchLogsException(String message, Throwable cause) {
        super(message, cause);
    }
}
