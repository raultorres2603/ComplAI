package cat.complai.openrouter.interfaces;

import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OutputFormat;

public interface IOpenRouterService {
    OpenRouterResponseDto ask(String question);

    OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format);
}
