package cat.complai.openrouter.interfaces.services;

import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Singleton
public class OpenRouterServices implements IOpenRouterService {

    private final HttpWrapper httpWrapper;

    @Inject
    public OpenRouterServices(HttpWrapper httpWrapper) {
        this.httpWrapper = Objects.requireNonNull(httpWrapper, "httpWrapper");
    }

    @Override
    public OpenRouterResponseDto ask(String question) {
        if (question == null || question.isBlank()) {
            return new OpenRouterResponseDto(false, null, "Question must not be empty.", null);
        }

        String prompt = String.format(
                "User question (from a resident). Please answer only if the question is about El Prat de Llobregat. If it's not about El Prat de Llobregat, politely say you can't help with that request.\n\nQuestion:\n%s\n\nPlease answer concisely and provide relevant local information or guidance.",
                question.trim()
        );

        return callOpenRouterAndExtract(prompt);
    }

    @Override
    public OpenRouterResponseDto redactComplaint(String complaint) {
        if (complaint == null || complaint.isBlank()) {
            return new OpenRouterResponseDto(false, null, "Complaint must not be empty.", null);
        }

        String prompt = String.format(
                "Please redact a formal, civil, and concise letter addressed to the City Hall (Ajuntament) of El Prat de Llobregat based on the following complaint. If the complaint is not about El Prat de Llobregat, politely say you can't help with that request. Include a short summary, the specific request or remedy sought, and a polite closing. Complaint text:\n%s",
                complaint.trim()
        );

        return callOpenRouterAndExtract(prompt);
    }

    /**
     * Detect whether the assistant explicitly refused because the request is not about El Prat.
     * This uses a small set of phrase checks that mirror the system prompt instruction.
     */
    private boolean aiRefusedAsNotAboutElPrat(String aiMessage) {
        if (aiMessage == null) return false;
        String lower = aiMessage.toLowerCase();
        return lower.contains("can't help") || lower.contains("cannot help") || lower.contains("can't help with that request") || lower.contains("no puc ajudar") || lower.contains("no puedo ayudar");
    }

    private OpenRouterResponseDto callOpenRouterAndExtract(String prompt) {
        try {
            CompletableFuture<HttpDto> future = httpWrapper.postToOpenRouterAsync(prompt);
            HttpDto dto = future.get(30, TimeUnit.SECONDS);
            if (dto == null) {
                return new OpenRouterResponseDto(false, null, "No response from AI service.", null);
            }
            if (dto.getError() != null && !dto.getError().isBlank()) {
                return new OpenRouterResponseDto(false, dto.getMessage(), dto.getError(), dto.getStatusCode());
            }
            if (dto.getMessage() != null && !dto.getMessage().isBlank()) {
                // If the AI refused because it's not about El Prat, map to a standardized error
                if (aiRefusedAsNotAboutElPrat(dto.getMessage())) {
                    return new OpenRouterResponseDto(false, null, "Request is not about El Prat de Llobregat.", dto.getStatusCode());
                }
                return new OpenRouterResponseDto(true, dto.getMessage(), null, dto.getStatusCode());
            }
            return new OpenRouterResponseDto(false, null, "AI returned no message.", dto.getStatusCode());
        } catch (TimeoutException te) {
            return new OpenRouterResponseDto(false, null, "AI service timed out.", null);
        } catch (Exception e) {
            return new OpenRouterResponseDto(false, null, e.getMessage(), null);
        }
    }
}
