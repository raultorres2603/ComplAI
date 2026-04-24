package cat.complai.controllers.feedback.dto;

import io.micronaut.core.annotation.Introspected;

/**
 * Response body for HTTP 202 Accepted when feedback is successfully queued.
 *
 * <p>The endpoints returns this DTO immediately; the feedback is processed
 * asynchronously by the Lambda worker.
 */
@Introspected
public record FeedbackAcceptedDto(
        String feedbackId,
        String status,
        String message
) {}
