package cat.complai.controllers.feedback.dto;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for the POST /complai/feedback endpoint.
 *
 * <p>All fields are required. Validation occurs in FeedbackPublisherService
 * which returns typed error codes rather than throwing exceptions.
 */
@Introspected
public record FeedbackRequest(
        @NotBlank(message = "userName must not be blank") String userName,
        @NotBlank(message = "idUser must not be blank") String idUser,
        @NotBlank(message = "message must not be blank") String message
) {}
