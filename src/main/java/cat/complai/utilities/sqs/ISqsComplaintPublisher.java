package cat.complai.utilities.sqs;

import cat.complai.dto.sqs.RedactSqsMessage;

/**
 * Interface for publishing complaint letter generation requests to the SQS
 * redact queue.
 *
 * <p>
 * Depend on this abstraction instead of the concrete
 * {@link SqsComplaintPublisher} class
 * to follow the Dependency Inversion Principle.
 * </p>
 */
public interface ISqsComplaintPublisher {

    /**
     * Serialises the message to JSON and enqueues it on the redact queue.
     *
     * @param message the complaint letter generation request
     * @throws RuntimeException if the queue depth is exceeded, serialisation
     *                          fails, or the SQS call is unsuccessful
     */
    void publish(RedactSqsMessage message);
}
