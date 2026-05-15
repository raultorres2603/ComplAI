package cat.complai.services.openrouter;

import cat.complai.services.openrouter.conversation.ConversationManagementService;
import java.util.List;

/**
 * Result type wrapping clarification candidates when a procedure query is
 * ambiguous.
 */
public record ProcedureAmbiguityResult(
        List<ConversationManagementService.ClarificationCandidate> candidates) {
}
