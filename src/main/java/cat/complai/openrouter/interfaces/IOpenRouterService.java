package cat.complai.openrouter.interfaces;

import cat.complai.openrouter.dto.OpenRouterResponseDto;

public interface IOpenRouterService {
    OpenRouterResponseDto ask(String question);

    OpenRouterResponseDto redactComplaint(String complaint);
}
