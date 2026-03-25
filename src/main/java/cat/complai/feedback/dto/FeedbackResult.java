package cat.complai.feedback.dto;

import cat.complai.feedback.controllers.dto.FeedbackAcceptedDto;
import io.micronaut.core.annotation.Introspected;

/**
 * Sealed record hierarchy for typed result handling in the feedback service.
 *
 * <p>Allows controllers to pattern-match on Success/Error without exception-driven
 * control flow. Similar to OpenRouter's OpenRouterErrorCode pattern.
 */
public sealed interface FeedbackResult {

    /**
     * Successful feedback publication.
     */
    @Introspected
    record Success(FeedbackAcceptedDto data) implements FeedbackResult {}

    /**
     * Feedback publication failure with typed error code.
     */
    @Introspected
    record Error(FeedbackErrorCode errorCode, String message) implements FeedbackResult {}
}
