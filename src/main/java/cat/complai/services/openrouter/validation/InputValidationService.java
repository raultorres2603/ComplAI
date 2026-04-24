package cat.complai.services.openrouter.validation;

import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import io.micronaut.context.annotation.Value;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Validates raw user input at the service boundary before it is forwarded to
 * the AI.
 *
 * <p>
 * Checks include blank/null guards, maximum character length (configurable via
 * {@code complai.input.max-length-chars}), and anonymity detection for the
 * redact flow.
 */
@Singleton
public class InputValidationService {

    private static final Logger logger = Logger.getLogger(InputValidationService.class.getName());
    private final int maxInputLength;

    /**
     * Constructs the validation service with a configurable maximum input length.
     *
     * @param maxInputLength maximum allowed number of characters in a user input;
     *                       defaults to 5 000
     */
    @Inject
    public InputValidationService(@Value("${complai.input.max-length-chars:5000}") int maxInputLength) {
        this.maxInputLength = maxInputLength;
    }

    /**
     * Validates a question for the ask flow.
     *
     * @param question the raw question text from the user
     * @return an {@link Optional} containing a validation error DTO, or empty if
     *         valid
     */
    public Optional<OpenRouterResponseDto> validateQuestion(String question) {
        if (question == null || question.isBlank()) {
            return Optional.of(new OpenRouterResponseDto(false, null, "Question must not be empty.", null,
                    OpenRouterErrorCode.VALIDATION));
        }
        if (question.length() > maxInputLength) {
            return Optional.of(new OpenRouterResponseDto(false, null,
                    "Question exceeds maximum allowed length (" + maxInputLength + " characters).", null,
                    OpenRouterErrorCode.VALIDATION));
        }
        return Optional.empty();
    }

    /**
     * Validates a complaint for the redact flow, also checking for anonymity
     * requests.
     *
     * @param complaint the raw complaint text from the user
     * @return an {@link Optional} containing a validation error DTO, or empty if
     *         valid
     */
    public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
        if (complaint == null || complaint.isBlank()) {
            return Optional.of(new OpenRouterResponseDto(false, null, "Complaint must not be empty.", null,
                    OpenRouterErrorCode.VALIDATION));
        }
        if (complaint.length() > maxInputLength) {
            return Optional.of(new OpenRouterResponseDto(false, null,
                    "Complaint exceeds maximum allowed length (" + maxInputLength + " characters).", null,
                    OpenRouterErrorCode.VALIDATION));
        }
        if (requestsAnonymity(complaint)) {
            return Optional.of(new OpenRouterResponseDto(false, null,
                    "Anonymous complaints cannot be drafted. The Ajuntament requires full name and ID/DNI/NIF on all formal complaints.",
                    null, OpenRouterErrorCode.VALIDATION));
        }
        return Optional.empty();
    }

    /**
     * Detects whether the user's message explicitly asks for the complaint to be
     * anonymous.
     * The Ajuntament does not accept anonymous complaints, so we reject these early
     * and clearly
     * rather than letting the AI draft an unusable letter.
     * The check is intentionally conservative: we only match phrases where the user
     * clearly states
     * they want anonymity, not every mention of the word "anonymous".
     */
    private boolean requestsAnonymity(String text) {
        if (text == null)
            return false;
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
