package cat.complai.openrouter.services;

import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micronaut.context.annotation.Value;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.*;

import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OutputFormat;
import cat.complai.openrouter.helpers.AiParsed;
import cat.complai.openrouter.helpers.PdfGenerator;

@Singleton
public class OpenRouterServices implements IOpenRouterService {

    private static final int MAX_HISTORY_TURNS = 10;
    private final HttpWrapper httpWrapper;
    private final RedactPromptBuilder promptBuilder;
    private final Logger logger = Logger.getLogger(OpenRouterServices.class.getName());
    private final Cache<String, List<MessageEntry>> conversationCache;
    // Stores the original complaint text for conversations where identity was missing on the first
    // turn. Keyed by conversationId. Cleared once the identity is complete and the letter is drafted.
    private final Cache<String, String> pendingComplaintCache;
    private final int maxInputLength;
    private final int overallTimeoutSeconds;

    public record MessageEntry(String role, String content) {}

    @Inject
    public OpenRouterServices(HttpWrapper httpWrapper,
                              @Value("${complai.input.max-length-chars:5000}") int maxInputLength,
                              @Value("${OPENROUTER_OVERALL_TIMEOUT_SECONDS:60}") int overallTimeoutSeconds,
                              RedactPromptBuilder promptBuilder) {
        this.httpWrapper    = Objects.requireNonNull(httpWrapper, "httpWrapper");
        this.promptBuilder  = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.conversationCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
        this.pendingComplaintCache = Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .build();
        this.maxInputLength = maxInputLength;
        this.overallTimeoutSeconds = (overallTimeoutSeconds > 0) ? overallTimeoutSeconds : 30;
    }

    // -------------------------------------------------------------------------
    // IOpenRouterService
    // -------------------------------------------------------------------------

    @Override
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


    @Override
    public OpenRouterResponseDto ask(String question, String conversationId) {
        logger.info("ask() called");
        if (question == null || question.isBlank()) {
            logger.fine("ask() rejected: empty question");
            return new OpenRouterResponseDto(false, null, "Question must not be empty.", null, OpenRouterErrorCode.VALIDATION);
        }
        if (question.length() > maxInputLength) {
            logger.fine("ask() rejected: question too long");
            return new OpenRouterResponseDto(false, null, "Question exceeds maximum allowed length (" + maxInputLength + " characters).", null, OpenRouterErrorCode.VALIDATION);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage()));
        String contextBlock = promptBuilder.buildProcedureContextBlock(question);
        if (contextBlock != null) {
            messages.add(Map.of("role", "system", "content", contextBlock));
        }
        if (conversationId != null && !conversationId.isBlank()) {
            List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
            if (history != null) {
                for (MessageEntry entry : history) {
                    messages.add(Map.of("role", entry.role(), "content", entry.content()));
                }
            }
        }
        messages.add(Map.of("role", "user", "content", question.trim()));

        logger.fine(() -> "ask() messages prepared: " + messages.size() + " total");
        OpenRouterResponseDto response = callOpenRouterAndExtract(messages);

        if (conversationId != null && !conversationId.isBlank() && response.getMessage() != null) {
            updateConversationHistory(conversationId, question, response.getMessage());
        }
        return response;
    }

    @Override
    public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
        logger.info("redactComplaint() called");

        Optional<OpenRouterResponseDto> validationError = validateRedactInput(complaint);
        if (validationError.isPresent()) {
            logger.fine("redactComplaint() rejected: " + validationError.get().getError());
            return validationError.get();
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage()));
        String contextBlock = promptBuilder.buildProcedureContextBlock(complaint);
        if (contextBlock != null) {
            messages.add(Map.of("role", "system", "content", contextBlock));
        }
        if (conversationId != null && !conversationId.isBlank()) {
            List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
            if (history != null) {
                for (MessageEntry entry : history) {
                    messages.add(Map.of("role", entry.role(), "content", entry.content()));
                }
            }
        }

        // Choose the prompt based on whether we have a complete identity.
        // When identity is incomplete, the AI is instructed to ask for the missing fields first.
        // The caller is expected to collect the AI's question, present it to the user, and
        // re-submit with the full identity in a subsequent request.
        final String userPrompt;
        final boolean identityComplete = identity != null && identity.isComplete();
        if (identityComplete) {
            // On the second turn the user's message contains the identity data they typed (e.g.
            // "My name is Raul Torres, DNI 49872354C, I live at Carrer Major 10"). The original
            // complaint was saved on the first turn. We combine both so that any optional contact
            // details the user included in their identity reply (address, phone, etc.) are visible
            // to the prompt and can be included in the letter.
            String originalComplaint = (conversationId != null && !conversationId.isBlank())
                    ? pendingComplaintCache.getIfPresent(conversationId)
                    : null;
            if (originalComplaint != null) {
                pendingComplaintCache.invalidate(conversationId);
                logger.fine("redactComplaint() using stored original complaint for conversationId=" + conversationId);
            }
            String complaintForPrompt = (originalComplaint != null)
                    ? originalComplaint + "\n\n" + complaint
                    : complaint;
            userPrompt = promptBuilder.buildRedactPromptWithIdentity(complaintForPrompt, identity);
        } else {
            if (conversationId != null && !conversationId.isBlank()) {
                pendingComplaintCache.put(conversationId, complaint);
                logger.fine("redactComplaint() saved original complaint for conversationId=" + conversationId);
            }
            userPrompt = promptBuilder.buildRedactPromptRequestingIdentity(complaint, identity);
        }

        messages.add(Map.of("role", "user", "content", userPrompt));

        logger.fine(() -> "redactComplaint() messages prepared: " + messages.size() + " total, identityComplete=" + identityComplete);
        OpenRouterResponseDto aiDto = callOpenRouterAndExtract(messages);

        // Propagate any non-success result immediately.
        if (aiDto.getErrorCode() != OpenRouterErrorCode.NONE) {
            return aiDto;
        }

        if (conversationId != null && !conversationId.isBlank() && aiDto.getMessage() != null) {
            updateConversationHistory(conversationId, userPrompt, aiDto.getMessage());
        }

        AiParsed parsed = AiParsed.parseAiFormatHeader(aiDto.getMessage());
        OutputFormat effectiveFormat = format == null ? OutputFormat.AUTO : format;

        if (effectiveFormat == OutputFormat.AUTO && parsed.format() != null && parsed.format() != OutputFormat.AUTO) {
            effectiveFormat = parsed.format();
        }

        // When identity is incomplete the AI response is a question asking the user for missing
        // fields. Return it as a JSON success so the client can display the question.
        // Exception: if effectiveFormat is PDF (AI extracted identity and promoted format), fall
        // through to PDF generation.
        if (!identityComplete && effectiveFormat != OutputFormat.PDF) {
            return new OpenRouterResponseDto(true, parsed.message(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
        }

        // Graceful fallback when the AI omits the required JSON header.
        if (parsed.format() == null || parsed.format() == OutputFormat.AUTO) {
            String rawPreview = aiDto.getMessage() == null ? "<null>"
                    : (aiDto.getMessage().length() > 200 ? aiDto.getMessage().substring(0, 200) + "..." : aiDto.getMessage());
            logger.warning("AI response missing required JSON header; raw response prefix: " + rawPreview);

            if (effectiveFormat == OutputFormat.PDF) {
                String letterBody = aiDto.getMessage() == null ? "" : aiDto.getMessage();
                if (letterBody.isBlank()) {
                    return new OpenRouterResponseDto(false, null, "AI returned an empty response; cannot produce PDF.", aiDto.getStatusCode(), OpenRouterErrorCode.UPSTREAM);
                }
                try {
                    byte[] pdf = PdfGenerator.generatePdf(letterBody);
                    return new OpenRouterResponseDto(true, null, null, null, OpenRouterErrorCode.NONE, pdf);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "PDF generation failed (headerless fallback)", e);
                    return new OpenRouterResponseDto(false, null, "PDF generation failed: " + e.getMessage(), null, OpenRouterErrorCode.INTERNAL);
                }
            }
            return new OpenRouterResponseDto(true, aiDto.getMessage(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
        }

        if (effectiveFormat != OutputFormat.PDF) {
            return new OpenRouterResponseDto(true, parsed.message(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
        }

        if (parsed.message().isBlank()) {
            logger.warning("AI emitted format header but letter body is empty. Original response: " + aiDto.getMessage());
            return new OpenRouterResponseDto(false, null, "AI returned a format header but no letter body.", aiDto.getStatusCode(), OpenRouterErrorCode.UPSTREAM);
        }

        try {
            byte[] pdf = PdfGenerator.generatePdf(parsed.message());
            return new OpenRouterResponseDto(true, null, null, null, OpenRouterErrorCode.NONE, pdf);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "PDF generation failed", e);
            return new OpenRouterResponseDto(false, null, "PDF generation failed: " + e.getMessage(), null, OpenRouterErrorCode.INTERNAL);
        }
    }


    /**
     * Detects whether the user's message explicitly asks for the complaint to be anonymous.
     * The Ajuntament does not accept anonymous complaints, so we reject these early and clearly
     * rather than letting the AI draft an unusable letter.
     *
     * The check is intentionally conservative: we only match phrases where the user clearly states
     * they want anonymity, not every mention of the word "anonymous".
     */
    private boolean requestsAnonymity(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT)
                .replace("\u2018", "'").replace("\u2019", "'")
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


    /**
     * Detect whether the assistant explicitly refused because the request is not about El Prat.
     * This uses a small set of phrase checks that mirror the system prompt instruction.
     */
    private boolean aiRefusedAsNotAboutElPrat(String aiMessage) {
        if (aiMessage == null) return false;
        // Normalize common quotes/apostrophes for more reliable matching
        String normalized = aiMessage.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"');
        String lower = normalized.toLowerCase(Locale.ROOT).trim();
        // Remove punctuation for more robust matching (fix regex)
        String lowerNoPunct = lower.replaceAll("[.,;:!?()\\[\\]{}-]", " ").replaceAll("\\s+", " ").trim();

        // Common refusal patterns in English, Spanish and Catalan
        String[] refusalPhrases = new String[] {
                "can't help with",
                "cannot help with",
                "can't help",
                "cannot help",
                "can't assist",
                "cannot assist",
                "i'm sorry, i can't",
                "i'm sorry i can't",
                "i'm sorry, i cannot",
                "i'm sorry i cannot",
                "i cannot",
                "i can't",
                "i am unable to",
                "i'm unable to",
                "i cannot help",
                "i can't help",
                "cannot provide",
                "can't provide",
                "no puc ajudar",
                "no puc ajudar amb",
                "no puedo ayudar",
                "no puedo ayudar con",
                "lo siento, no puedo ayudar",
                "lo siento, no puedo",
                "no puedo",
                "no puc",
                "no puc ajudar",
                // Add more variants
                "not about el prat",
                "only answer about el prat",
                "only questions about el prat",
                "solo puedo responder sobre el prat",
                "només puc respondre sobre el prat",
                "solamente puedo responder sobre el prat",
                "solament puc respondre sobre el prat"
        };

        for (String p : refusalPhrases) {
            String pNoPunct = p.replaceAll("[.,;:!?()\\[\\]{}-]", " ").trim();
            if (lower.contains(p) || lowerNoPunct.contains(pNoPunct)) {
                logger.fine("Refusal detected by phrase: '" + p + "' in: " + lower);
                return true;
            }
        }

        // Also check for explicit mention that the model can only answer about El Prat
        if (lower.contains("el prat") && (lower.contains("only") || lower.contains("solament") || lower.contains("solo") || lower.contains("only about"))) {
            logger.fine("Refusal detected by El Prat + only/solament/solo/only about");
            return true;
        }
        return false;
    }

    private OpenRouterResponseDto callOpenRouterAndExtract(List<Map<String, Object>> messages) {
        logger.fine("callOpenRouterAndExtract: calling HttpWrapper");
        try {
            CompletableFuture<HttpDto> future = httpWrapper.postToOpenRouterAsync(messages);
            HttpDto dto = future.get(overallTimeoutSeconds, TimeUnit.SECONDS);
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
                    // Return success=false but PRESERVE the AI message so callers can log/display the assistant's text
                    return new OpenRouterResponseDto(false, dto.message(), "Request is not about El Prat de Llobregat.", dto.statusCode(), OpenRouterErrorCode.REFUSAL);
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

    private void updateConversationHistory(String conversationId, String userMessage, String assistantMessage) {
        if (conversationId == null || conversationId.isBlank() || assistantMessage == null) return;
        List<MessageEntry> history = conversationCache.getIfPresent(conversationId);
        if (history == null) history = new ArrayList<>();
        if (userMessage != null && !userMessage.isBlank()) {
            history.add(new MessageEntry("user", userMessage.trim()));
        }
        history.add(new MessageEntry("assistant", assistantMessage));
        // Cap history
        if (history.size() > MAX_HISTORY_TURNS * 2) {
            history = history.subList(history.size() - MAX_HISTORY_TURNS * 2, history.size());
        }
        conversationCache.put(conversationId, history);
    }
}

// Timeout is now configurable via the OPENROUTER_OVERALL_TIMEOUT_SECONDS environment variable (default 30s).
