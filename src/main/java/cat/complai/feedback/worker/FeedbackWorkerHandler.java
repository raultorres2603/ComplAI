package cat.complai.feedback.worker;

import cat.complai.feedback.dto.FeedbackSqsMessage;
import cat.complai.feedback.s3.S3FeedbackUploader;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.function.aws.MicronautRequestHandler;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * AWS Lambda handler for processing feedback messages from SQS.
 *
 * <p>Extends {@link MicronautRequestHandler} to receive SQS events and return batch
 * failure responses. The handler processes each message and reports failures for
 * retry/DLQ routing.
 *
 * <p>Lambda configuration:
 * <ul>
 *   <li>Handler: cat.complai.feedback.worker.FeedbackWorkerHandler::handleRequest</li>
 *   <li>Memory: 512 MB</li>
 *   <li>Timeout: 60 seconds</li>
 *   <li>Event source: SQS feedback queue</li>
 * </ul>
 */
public class FeedbackWorkerHandler extends MicronautRequestHandler<SQSEvent, SQSBatchResponse> {

    private final S3FeedbackUploader s3Uploader;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Logger logger = Logger.getLogger(FeedbackWorkerHandler.class.getName());

    @Inject
    public FeedbackWorkerHandler(S3FeedbackUploader s3Uploader) {
        this.s3Uploader = s3Uploader;
    }

    /**
     * Processes a batch of SQS messages from the feedback queue.
     *
     * <p>For each message:
     * <ol>
     *   <li>Deserialize the JSON body to FeedbackSqsMessage</li>
     *   <li>Instantiate FeedbackProcessor and call process()</li>
     *   <li>If processing succeeds, continue to next message</li>
     *   <li>If processing fails, add the messageId to the failed list</li>
     * </ol>
     *
     * <p>Failed message IDs are returned in SQSBatchResponse.batchItemFailures.
     * Lambda automatically retries failed items using the queue's delaySeconds
     * and maxReceiveCount settings. After maxReceiveCount failures, the message
     * is sent to the DLQ.
     *
     * @param event the SQS batch event
     * @return batch response with failed message IDs (empty list if all succeeded)
     */
    @Override
    public SQSBatchResponse execute(SQSEvent event) {
        List<SQSBatchResponse.BatchItemFailure> batchItemFailures = new ArrayList<>();

        if (event == null || event.getRecords() == null || event.getRecords().isEmpty()) {
            logger.info("Feedback worker received empty batch");
            return new SQSBatchResponse(batchItemFailures);
        }

        logger.info(() -> "Feedback worker processing batch — recordCount=" + event.getRecords().size());

        FeedbackProcessor processor = new FeedbackProcessor(s3Uploader);

        for (SQSEvent.SQSMessage sqsMessage : event.getRecords()) {
            try {
                String messageId = sqsMessage.getMessageId();
                String body = sqsMessage.getBody();

                logger.info(() -> "Processing feedback message — messageId=" + messageId);

                // Deserialize the JSON body
                FeedbackSqsMessage feedbackMessage = mapper.readValue(body, FeedbackSqsMessage.class);

                // Process (serialize to JSON and upload to S3)
                processor.process(feedbackMessage);

                logger.info(() -> "Feedback message processed successfully — messageId=" + messageId
                        + " feedbackId=" + feedbackMessage.feedbackId());

            } catch (Exception e) {
                String messageId = sqsMessage.getMessageId();
                logger.log(Level.SEVERE, "Feedback message processing failed — messageId=" + messageId
                        + " error=" + e.getMessage(), e);

                // Report the failure; Lambda will retry or send to DLQ
                batchItemFailures.add(new SQSBatchResponse.BatchItemFailure(messageId));
            }
        }

        logger.info(() -> "Feedback worker batch complete — failureCount=" + batchItemFailures.size());
        return new SQSBatchResponse(batchItemFailures);
    }
}
