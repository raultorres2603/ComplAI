package cat.complai.openrouter.interfaces;

import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OutputFormat;

public interface IOpenRouterService {
    OpenRouterResponseDto ask(String question, String conversationId);

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
