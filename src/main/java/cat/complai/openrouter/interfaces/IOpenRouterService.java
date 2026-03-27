package cat.complai.openrouter.interfaces;

import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OutputFormat;
import org.reactivestreams.Publisher;

import java.util.Optional;

public interface IOpenRouterService {
    OpenRouterResponseDto ask(String question, String conversationId, String cityId);

    /**
     * Streams an answer to {@code question} as raw text deltas via SSE.
     * After the stream completes, the assembled response is saved to conversation history.
     */
    Publisher<String> streamAsk(String question, String conversationId, String cityId);

    /**
     * Validates a redact complaint input at the boundary (null/blank, length, anonymity).
     * City-agnostic — validation rules are the same regardless of which municipality the
     * request is for.
     */
    Optional<OpenRouterResponseDto> validateRedactInput(String complaint);

    /**
     * Drafts a formal complaint letter addressed to the Ajuntament.
     *
     * @param complaint      the complaint text provided by the user
     * @param format         the desired output format (PDF, JSON, or AUTO)
     * @param conversationId optional multi-turn conversation context key
     * @param identity       optional complainant identity (name, surname, ID number).
     *                       When null or incomplete the AI is instructed to request the missing
     *                       fields before drafting. When the user explicitly requests anonymity
     *                       the service rejects with VALIDATION — the Ajuntament does not accept
     *                       anonymous complaints.
     * @param cityId         city identifier from the caller's JWT, used to load the correct
     *                       procedures context for RAG
     */
    OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format,
                                          String conversationId, ComplainantIdentity identity,
                                          String cityId);
}
