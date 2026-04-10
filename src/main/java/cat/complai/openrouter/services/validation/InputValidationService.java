package cat.complai.openrouter.services.validation;

import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import io.micronaut.context.annotation.Value;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Validates user-supplied text inputs at the service boundary.
 *
 * <p>All validation is performed before any AI call is made, ensuring that invalid or
 * policy-violating inputs are rejected cheaply. Two separate validation paths exist:
 * <ul>
 *   <li>{@link #validateQuestion(String)} — used by the {@code /ask} flow</li>
 *   <li>{@link #validateRedactInput(String)} — used by the {@code /redact} flow; additionally
 *       rejects requests that explicitly ask for an anonymous complaint, which the Ajuntament
 *       cannot process</li>
 * </ul>
 *
 * <p>The maximum allowed input length is controlled by the
 * {@code complai.input.max-length-chars} property (default 5,000 characters).
 */
@Singleton
public class InputValidationService {
    
    private static final Logger logger = Logger.getLogger(InputValidationService.class.getName());
    private final int maxInputLength;
    
    @Inject
    public InputValidationService(@Value("${complai.input.max-length-chars:5000}") int maxInputLength) {
        this.maxInputLength = maxInputLength;
    }
    
    /**
     * Validates a citizen question for the {@code /ask} flow.
     *
     * @param question the raw question text
     * @return an empty {@link Optional} when valid; a {@link OpenRouterResponseDto} with
     *         {@link cat.complai.openrouter.dto.OpenRouterErrorCode#VALIDATION} when invalid
     */
    public Optional<OpenRouterResponseDto> validateQuestion(String question) {
        if (question == null || question.isBlank()) {
            return Optional.of(new OpenRouterResponseDto(false, null, "Question must not be empty.", null, OpenRouterErrorCode.VALIDATION));
        }
        if (question.length() > maxInputLength) {
            return Optional.of(new OpenRouterResponseDto(false, null, 
                "Question exceeds maximum allowed length (" + maxInputLength + " characters).", null, OpenRouterErrorCode.VALIDATION));
        }
        return Optional.empty();
    }
    
    /**
     * Validates a complaint text for the {@code /redact} flow.
     *
     * <p>In addition to the null/blank/length checks performed by
     * {@link #validateQuestion(String)}, this method rejects any text that explicitly
     * requests anonymity, since the Ajuntament does not process anonymous complaints.
     *
     * @param complaint the raw complaint text
     * @return an empty {@link Optional} when valid; a {@link OpenRouterResponseDto} with
     *         {@link cat.complai.openrouter.dto.OpenRouterErrorCode#VALIDATION} when invalid
     */
    public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
        if (complaint == null || complaint.isBlank()) {
            return Optional.of(new OpenRouterResponseDto(false, null, "Complaint must not be empty.", null, OpenRouterErrorCode.VALIDATION));
        }
        if (complaint.length() > maxInputLength) {
            return Optional.of(new OpenRouterResponseDto(false, null,
                "Complaint exceeds maximum allowed length (" + maxInputLength + " characters).", null, OpenRouterErrorCode.VALIDATION));
        }
        if (requestsAnonymity(complaint)) {
            return Optional.of(new OpenRouterResponseDto(false, null,
                "Anonymous complaints cannot be drafted. The Ajuntament requires full name and ID/DNI/NIF on all formal complaints.",
                null, OpenRouterErrorCode.VALIDATION));
        }
        return Optional.empty();
    }
    
    /**
     * Detects whether the user's message explicitly asks for the complaint to be anonymous.
     * The Ajuntament does not accept anonymous complaints, so we reject these early and clearly
     * rather than letting the AI draft an unusable letter.
     * The check is intentionally conservative: we only match phrases where the user clearly states
     * they want anonymity, not every mention of the word "anonymous".
     */
    private boolean requestsAnonymity(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT)
                .replace("'", "'").replace("'", "'")
                .replaceAll("[.,;:!?()\\[\\]{}-]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String[] anonymousPhrases = {
                "want to be anonymous",
                "remain anonymous",
                "stay anonymous",
                "keep it anonymous",
                "make it anonymous",
                "anonymously",
                "quiero ser anónimo",
                "quiero que sea anónimo",
                "de forma anónima",
                "de manera anónima",
                "en forma anónima",
                "quiero hacerlo anónimo",
                "vull ser anònim",
                "vull que sigui anònim",
                "de forma anònima",
                "de manera anònima",
                "vull fer-ho anònim"
        };
        for (String phrase : anonymousPhrases) {
            if (lower.contains(phrase)) {
                logger.fine("Anonymous intent detected by phrase: '" + phrase + "'");
                return true;
            }
        }
        return false;
    }
}
