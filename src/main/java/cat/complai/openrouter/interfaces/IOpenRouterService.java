package cat.complai.openrouter.interfaces;

import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OutputFormat;

public interface IOpenRouterService {
    OpenRouterResponseDto ask(String question, String conversationId);

    OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId);
}
