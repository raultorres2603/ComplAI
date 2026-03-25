package cat.complai.feedback.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;

/**
 * SQS message payload for the async feedback processing flow.
 *
 * <p>This record is the contract between the API Lambda (publisher) and the worker Lambda
 * (consumer). All fields are required and are included in the JSON message published to SQS.
 *
 * <p>The worker Lambda deserializes this and uploads the feedback JSON to S3 with the
 * constructed S3 key.
 */
@Introspected
public record FeedbackSqsMessage(
        String feedbackId,
        long timestamp,
        String city,
        String userName,
        String idUser,
        String message
) {
    @JsonCreator
    public static FeedbackSqsMessage fromJson(
            @JsonProperty("feedbackId") String feedbackId,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("city") String city,
            @JsonProperty("userName") String userName,
            @JsonProperty("idUser") String idUser,
            @JsonProperty("message") String message) {
        return new FeedbackSqsMessage(feedbackId, timestamp, city, userName, idUser, message);
    }
}
