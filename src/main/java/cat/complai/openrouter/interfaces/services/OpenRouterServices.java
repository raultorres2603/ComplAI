package cat.complai.openrouter.interfaces.services;

import cat.complai.openrouter.interfaces.IOpenRouterService;
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
    public String ask(String question) {
        if (question == null || question.isBlank()) {
            return "Question must not be empty.";
        }

        // If the question does not mention El Prat, refuse.
        if (isNotAboutElPrat(question)) {
            return "I can only assist with matters related to El Prat de Llobregat.";
        }

        String prompt = String.format(
                "User question about El Prat de Llobregat:\n%s\n\nPlease answer concisely and provide relevant local information or guidance.",
                question.trim()
        );

        return callOpenRouterAndExtract(prompt);
    }

    @Override
    public String redactComplaint(String complaint) {
        if (complaint == null || complaint.isBlank()) {
            return "Complaint must not be empty.";
        }

        // If the complaint does not mention El Prat, refuse.
        if (isNotAboutElPrat(complaint)) {
            return "I can only help draft complaints or letters that are about El Prat de Llobregat.";
        }

        String prompt = String.format(
                "Please redact a formal, civil, and concise letter addressed to the City Hall (Ajuntament) of El Prat de Llobregat based on the following complaint. Include a short summary, the specific request or remedy sought, and a polite closing. Complaint text:\n%s",
                complaint.trim()
        );

        return callOpenRouterAndExtract(prompt);
    }

    private boolean isNotAboutElPrat(String text) {
        if (text == null) return true;
        String lower = text.toLowerCase();
        return !(lower.contains("el prat de llobregat") || lower.contains("el prat") || lower.contains("prat de llobregat"));
    }

    private String callOpenRouterAndExtract(String prompt) {
        try {
            CompletableFuture<HttpDto> future = httpWrapper.postToOpenRouterAsync(prompt);
            HttpDto dto = future.get(30, TimeUnit.SECONDS);
            if (dto == null) {
                return "No response from AI service.";
            }
            // If the wrapper returned an error, surface it directly to the caller.
            if (dto.getError() != null && !dto.getError().isBlank()) {
                return dto.getError();
            }
            if (dto.getMessage() != null && !dto.getMessage().isBlank()) {
                return dto.getMessage();
            }
            return "AI returned no message.";
        } catch (TimeoutException te) {
            return "AI service timed out.";
        } catch (Exception e) {
            return e.getMessage();
        }
    }
}
