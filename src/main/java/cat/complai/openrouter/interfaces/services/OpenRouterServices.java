package cat.complai.openrouter.interfaces.services;

import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class OpenRouterServices implements IOpenRouterService {

    private final HttpWrapper httpWrapper;
    private final Logger logger = Logger.getLogger(OpenRouterServices.class.getName());

    @Inject
    public OpenRouterServices(HttpWrapper httpWrapper) {
        this.httpWrapper = Objects.requireNonNull(httpWrapper, "httpWrapper");
    }

    @Override
    public OpenRouterResponseDto ask(String question) {
        logger.info("ask() called");
        if (question == null || question.isBlank()) {
            logger.fine("ask() rejected: empty question");
            return new OpenRouterResponseDto(false, null, "Question must not be empty.", null, OpenRouterErrorCode.VALIDATION);
        }

        String prompt = String.format(
                "User question (from a resident). Please answer only if the question is about El Prat de Llobregat. If it's not about El Prat de Llobregat, politely say you can't help with that request.\n\nQuestion:\n%s\n\nPlease answer concisely and provide relevant local information or guidance.",
                question.trim()
        );

        logger.fine(() -> "ask() prompt prepared: " + (question.length() > 200 ? question.substring(0, 200) + "..." : question));
        return callOpenRouterAndExtract(prompt);
    }

    @Override
    public OpenRouterResponseDto redactComplaint(String complaint) {
        logger.info("redactComplaint() called");
        if (complaint == null || complaint.isBlank()) {
            logger.fine("redactComplaint() rejected: empty complaint");
            return new OpenRouterResponseDto(false, null, "Complaint must not be empty.", null, OpenRouterErrorCode.VALIDATION);
        }

        String prompt = String.format(
                "Please redact a formal, civil, and concise letter addressed to the City Hall (Ajuntament) of El Prat de Llobregat based on the following complaint. If the complaint is not about El Prat de Llobregat, politely say you can't help with that request. Include a short summary, the specific request or remedy sought, and a polite closing. Complaint text:\n%s",
                complaint.trim()
        );

        logger.fine(() -> "redactComplaint() prompt prepared: " + (complaint.length() > 200 ? complaint.substring(0, 200) + "..." : complaint));
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
        logger.fine("callOpenRouterAndExtract: calling HttpWrapper");
        try {
            CompletableFuture<HttpDto> future = httpWrapper.postToOpenRouterAsync(prompt);
            HttpDto dto = future.get(30, TimeUnit.SECONDS);
            logger.fine(() -> "callOpenRouterAndExtract: received dto=" + (dto == null ? "null" : String.valueOf(dto.statusCode())));
            if (dto == null) {
                logger.warning("callOpenRouterAndExtract: No response from AI service");
                return new OpenRouterResponseDto(false, null, "No response from AI service.", null, OpenRouterErrorCode.UPSTREAM);
            }
            if (dto.error() != null && !dto.error().isBlank()) {
                logger.log(Level.WARNING, "AI wrapper returned error: {0}", dto.error());
                return new OpenRouterResponseDto(false, dto.message(), dto.error(), dto.statusCode(), OpenRouterErrorCode.UPSTREAM);
            }
            if (dto.message() != null && !dto.message().isBlank()) {
                // If the AI refused because it's not about El Prat, map to a standardized error
                if (aiRefusedAsNotAboutElPrat(dto.message())) {
                    logger.info("AI refused - not about El Prat");
                    return new OpenRouterResponseDto(false, null, "Request is not about El Prat de Llobregat.", dto.statusCode(), OpenRouterErrorCode.REFUSAL);
                }
                logger.fine("AI returned a message successfully");
                return new OpenRouterResponseDto(true, dto.message(), null, dto.statusCode(), OpenRouterErrorCode.NONE);
            }
            logger.warning("AI returned no message");
            return new OpenRouterResponseDto(false, null, "AI returned no message.", dto.statusCode(), OpenRouterErrorCode.UPSTREAM);
        } catch (TimeoutException te) {
            logger.log(Level.SEVERE, "AI service timed out", te);
            return new OpenRouterResponseDto(false, null, "AI service timed out.", null, OpenRouterErrorCode.TIMEOUT);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error calling AI service", e);
            return new OpenRouterResponseDto(false, null, e.getMessage(), null, OpenRouterErrorCode.INTERNAL);
        }
    }
}
