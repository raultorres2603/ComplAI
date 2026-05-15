package cat.complai.services.openrouter;

import cat.complai.config.CivicVocabularyConfig;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.OutputFormat;
import cat.complai.helpers.openrouter.CivicVocabularyService;
import cat.complai.helpers.openrouter.LanguageDetector;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.services.openrouter.ai.AiResponseProcessingService;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
import cat.complai.services.openrouter.validation.InputValidationService;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Orchestrates the complaint redact workflow: validates input, manages
 * identity collection (two-turn flow), builds AI prompts, and processes
 * the AI response.
 *
 * <p>This class was extracted from {@code OpenRouterServices} during the
 * god-class split.</p>
 */
@Singleton
public class RedactOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RedactOrchestrator.class);

    private final InputValidationService validationService;
    private final ConversationManagementService conversationService;
    private final AiResponseProcessingService aiResponseService;
    private final RedactPromptBuilder promptBuilder;
    private final CivicVocabularyService civicVocabularyService;
    private final CivicVocabularyConfig civicVocabularyConfig;

    @Inject
    public RedactOrchestrator(InputValidationService validationService,
                              ConversationManagementService conversationService,
                              AiResponseProcessingService aiResponseService,
                              RedactPromptBuilder promptBuilder,
                              CivicVocabularyService civicVocabularyService,
                              CivicVocabularyConfig civicVocabularyConfig) {
        this.validationService = validationService;
        this.conversationService = conversationService;
        this.aiResponseService = aiResponseService;
        this.promptBuilder = promptBuilder;
        this.civicVocabularyService = civicVocabularyService;
        this.civicVocabularyConfig = civicVocabularyConfig;
    }

    /**
     * Validates a redact complaint input at the boundary.
     */
    public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
        return validationService.validateRedactInput(complaint);
    }

    /**
     * Drafts a formal complaint letter addressed to the Ajuntament.
     */
    public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId,
                                                  ComplainantIdentity identity, String cityId) {
        int inputLength = complaint != null ? complaint.length() : 0;
        boolean identityProvided = identity != null && identity.isPartiallyProvided();
        log.info("redactComplaint() called — conversationId={} inputLength={} format={} identityProvided={} city={}",
                conversationId, inputLength, format, identityProvided, cityId);

        var validationError = validationService.validateRedactInput(complaint);
        if (validationError.isPresent()) {
            log.debug("redactComplaint() rejected — reason={} conversationId={}", validationError.get().getError(),
                    conversationId);
            return validationError.get();
        }

        // Expand complaint with civic vocabulary for better RAG retrieval
        String expandedComplaint = complaint;
        if (civicVocabularyConfig != null && civicVocabularyService != null
                && civicVocabularyConfig.isEnabled() && complaint != null && !complaint.isBlank()) {
            String detectedLang = LanguageDetector.detect(complaint);
            if (!"CA".equalsIgnoreCase(detectedLang)) {
                String vocabLang = switch (detectedLang.toUpperCase()) {
                    case "EN" -> "en";
                    case "ES" -> "es";
                    case "FR" -> "fr";
                    default -> null;
                };
                if (vocabLang != null) {
                    expandedComplaint = civicVocabularyService.expandQuery(complaint, vocabLang);
                    log.debug("redactComplaint() expanded complaint with civic vocabulary — originalLength={} expandedLength={}",
                            complaint.length(), expandedComplaint.length());
                }
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId)));

        // Only add procedure context if we have complete identity (ready to draft)
        String contextBlock;
        boolean hasCompleteIdentity = identity != null && identity.isComplete();
        if (hasCompleteIdentity) {
            contextBlock = promptBuilder.buildProcedureContextBlock(expandedComplaint, cityId);
            if (contextBlock != null) {
                messages.add(Map.of("role", "system", "content", contextBlock));
            }
        }

        // Add conversation history
        var history = conversationService.getConversationHistory(conversationId);
        conversationService.addToMessages(messages, history);

        // Choose the prompt based on whether we have a complete identity.
        String userPrompt = "";
        final boolean identityComplete = identity != null && identity.isComplete();
        if (identityComplete) {
            String originalComplaint = conversationService.getPendingComplaint(conversationId);
            if (originalComplaint != null) {
                conversationService.clearPendingComplaint(conversationId);
                log.debug("redactComplaint() resumed stored complaint — conversationId={} originalLength={}",
                        conversationId, originalComplaint.length());
            }
            String complaintForPrompt = (originalComplaint != null)
                    ? originalComplaint + "\n\n" + expandedComplaint
                    : expandedComplaint;
            if (complaintForPrompt != null) {
                userPrompt = promptBuilder.buildRedactPromptWithIdentity(complaintForPrompt, identity, cityId);
            }
        } else {
            if (conversationId != null && !conversationId.isBlank()) {
                conversationService.storePendingComplaint(conversationId, expandedComplaint);
                log.debug("redactComplaint() saved complaint for identity follow-up — conversationId={}",
                        conversationId);
            }
            userPrompt = promptBuilder.buildRedactPromptRequestingIdentity(expandedComplaint, identity, cityId);
        }

        messages.add(Map.of("role", "user", "content", userPrompt));

        log.debug("redactComplaint() messages prepared — messageCount={} identityComplete={} conversationId={}",
                messages.size(), identityComplete, conversationId);

        // No procedure/event context for complaints, use default cache key
        OpenRouterResponseDto aiDto = aiResponseService.callOpenRouterAndExtract(messages, cityId, 0, 0);

        // Process the AI response
        OpenRouterResponseDto processedResponse = aiResponseService.processComplaintResponse(aiDto, identityComplete);

        // Update conversation history if we have a valid response
        if (conversationId != null && !conversationId.isBlank() && processedResponse.getMessage() != null) {
            conversationService.updateConversationHistory(conversationId, userPrompt, processedResponse.getMessage());
        }

        return processedResponse;
    }
}
