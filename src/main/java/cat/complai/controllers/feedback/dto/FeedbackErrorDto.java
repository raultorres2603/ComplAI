package cat.complai.controllers.feedback.dto;

import io.micronaut.core.annotation.Introspected;

/**
 * Response body for error responses from the feedback endpoint.
 *
 * <p>Provides a stable JSON shape with success flag, numeric error code, and
 * a human-readable message.
 */
@Introspected
public record FeedbackErrorDto(
        boolean success,
        int errorCode,
        String message
) {}
