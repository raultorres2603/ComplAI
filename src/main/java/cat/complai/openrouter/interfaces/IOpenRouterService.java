package cat.complai.openrouter.interfaces;

import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OutputFormat;

import java.util.Optional;

public interface IOpenRouterService {
    OpenRouterResponseDto ask(String question, String conversationId);

    /**
     * Validates a redact complaint input at the boundary (null/blank, length, anonymity).
     * Returns {@link Optional#empty()} when the input is valid.
     * Returns a non-empty {@link Optional} containing the error DTO when the input must
     * be rejected, so the controller can short-circuit before doing any async work.
     *
     * <p>This is intentionally separated from {@link #redactComplaint} so the async
     * controller path can validate without executing the full synchronous flow.
     */
    Optional<OpenRouterResponseDto> validateRedactInput(String complaint);

    /**
     * Drafts a formal complaint letter addressed to the Ajuntament.
     *
     * @param complaint    the complaint text provided by the user
     * @param format       the desired output format (PDF, JSON, or AUTO)
     * @param conversationId optional multi-turn conversation context key
     * @param identity     optional complainant identity (name, surname, ID number).
     *                     When null or incomplete, the AI is instructed to request the missing
     *                     fields from the user before drafting.
     *                     When the user explicitly requests anonymity, the service rejects
     *                     the request with a VALIDATION error — the Ajuntament does not accept
     *                     anonymous complaints.
     */
    OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity);
}
