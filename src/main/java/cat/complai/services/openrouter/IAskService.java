package cat.complai.services.openrouter;

import cat.complai.dto.openrouter.OpenRouterResponseDto;

/**
 * Role interface for synchronous AI question answering.
 *
 * <p>Part of the Interface Segregation refactoring of {@link IOpenRouterService}.
 * Clients that only need to ask questions (e.g. {@code AskProcessor},
 * {@code StadisticsService}) should depend on this interface rather than
 * the composite {@link IOpenRouterService}.
 */
public interface IAskService {
    /**
     * Synchronously sends a question to the AI and returns the assembled response.
     *
     * @param question       the user's question text
     * @param conversationId optional conversation key for multi-turn context
     * @param cityId         city identifier used to select RAG context
     * @return the AI response DTO
     */
    OpenRouterResponseDto ask(String question, String conversationId, String cityId);

    /**
     * Synchronously sends a question to the AI and returns the assembled response,
     * with an explicit preferred language that overrides heuristic detection.
     *
     * @param question       the user's question text
     * @param conversationId optional conversation key for multi-turn context
     * @param cityId         city identifier used to select RAG context
     * @param preferredLang  preferred response language (CA, ES, EN, FR); if null
     *                       or blank, language is detected from the question text
     * @return the AI response DTO
     */
    OpenRouterResponseDto ask(String question, String conversationId, String cityId, String preferredLang);
}
