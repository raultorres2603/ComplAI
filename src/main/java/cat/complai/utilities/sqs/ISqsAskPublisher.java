package cat.complai.utilities.sqs;

import cat.complai.dto.sqs.AskSqsMessage;

/**
 * Interface for publishing Telegram ask requests to the SQS ask queue.
 *
 * <p>
 * Depend on this abstraction instead of the concrete {@link SqsAskPublisher}
 * class
 * to follow the Dependency Inversion Principle.
 * </p>
 */
public interface ISqsAskPublisher {

    /**
     * Serialises the message to JSON and enqueues it on the ask queue.
     *
     * @param message the ask request
     * @throws RuntimeException if the queue depth is exceeded, serialisation
     *                          fails, or the SQS call is unsuccessful
     */
    void publish(AskSqsMessage message);
}
