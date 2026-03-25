package cat.complai.feedback.controllers.dto;

import io.micronaut.core.annotation.Introspected;

/**
 * Request DTO for the POST /complai/feedback endpoint.
 *
 * <p>All fields are required. Validation occurs in FeedbackPublisherService
 * which returns typed error codes rather than throwing exceptions.
 */
@Introspected
public record FeedbackRequest(
        String userName,
        String idUser,
        String message
) {}
