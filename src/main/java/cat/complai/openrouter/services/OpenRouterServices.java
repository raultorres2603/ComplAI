package cat.complai.openrouter.services;

import cat.complai.openrouter.helpers.AiParsed;
import cat.complai.openrouter.services.validation.InputValidationService;
import cat.complai.openrouter.services.conversation.ConversationManagementService;
import cat.complai.openrouter.services.ai.AiResponseProcessingService;
import cat.complai.openrouter.services.procedure.ProcedureContextService;
import cat.complai.openrouter.services.procedure.ProcedureContextService.ProcedureContextResult;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.Source;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OutputFormat;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import io.micronaut.context.annotation.Value;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class OpenRouterServices implements IOpenRouterService {

    private final Logger logger = Logger.getLogger(OpenRouterServices.class.getName());
    private final InputValidationService validationService;
    private final ConversationManagementService conversationService;
    private final AiResponseProcessingService aiResponseService;
    private final ProcedureContextService procedureContextService;
    private final RedactPromptBuilder promptBuilder;

    /**
     * Estimates token count for text using OpenAI's rule of thumb: ~1 token per 4
     * characters.
     */
    private static int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    @Inject
    public OpenRouterServices(InputValidationService validationService,
            ConversationManagementService conversationService,
            AiResponseProcessingService aiResponseService,
            ProcedureContextService procedureContextService,
            RedactPromptBuilder promptBuilder) {
        this.validationService = validationService;
        this.conversationService = conversationService;
        this.aiResponseService = aiResponseService;
        this.procedureContextService = procedureContextService;
        this.promptBuilder = promptBuilder;
    }

    // -------------------------------------------------------------------------
    // IOpenRouterService
    // -------------------------------------------------------------------------

    @Override
    public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
        return validationService.validateRedactInput(complaint);
    }

    @Override
    public OpenRouterResponseDto ask(String question, String conversationId, String cityId) {
        int inputLength = question != null ? question.length() : 0;
        logger.info(() -> "ask() called — conversationId=" + conversationId + " inputLength=" + inputLength + " city="
                + cityId);

        var validationError = validationService.validateQuestion(question);
        if (validationError.isPresent()) {
            logger.fine(() -> "ask() rejected — reason=" + validationError.get().getError() + " conversationId="
                    + conversationId);
            return validationError.get();
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId)));

        // Parallelize RAG searches for procedure and event context
        // Start both searches concurrently to reduce latency
        long ragStartTime = System.currentTimeMillis();
        CompletableFuture<ProcedureContextResult> procFuture = procedureContextService
                .questionNeedsProcedureContext(question, cityId)
                        ? procedureContextService.buildProcedureContextResultAsync(question, cityId)
                        : CompletableFuture.completedFuture(null);

        CompletableFuture<ProcedureContextService.EventContextResult> eventFuture = procedureContextService
                .questionNeedsEventContext(question, cityId)
                        ? procedureContextService.buildEventContextResultAsync(question, cityId)
                        : CompletableFuture.completedFuture(null);

        // Wait for both searches to complete
        ProcedureContextResult procCtx = null;
        ProcedureContextService.EventContextResult eventCtx = null;
        try {
            CompletableFuture.allOf(procFuture, eventFuture).join();
            procCtx = procFuture.get();
            eventCtx = eventFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            logger.log(Level.WARNING,
                    "Async RAG search failed — error=" + e.getMessage() + " conversationId=" + conversationId, e);
            procCtx = null;
            eventCtx = null;
        }
        long ragEndTime = System.currentTimeMillis();
        logger.fine(() -> "ask() RAG search completed — time=" + (ragEndTime - ragStartTime) + "ms conversationId="
                + conversationId);

        // Add procedure context if available
        if (procCtx != null && procCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", procCtx.getContextBlock()));
        }

        // Add event context if available
        if (eventCtx != null && eventCtx.getContextBlock() != null) {
            messages.add(Map.of("role", "system", "content", eventCtx.getContextBlock()));
        }

        // Add conversation history
        var history = conversationService.getConversationHistory(conversationId);
        conversationService.addToMessages(messages, history);

        messages.add(Map.of("role", "user", "content", question.trim()));

        // Calculate and log context metrics
        final int systemTokens;
        final int historyTokens;
        final int userTokens;
        int tmpSystemTokens = 0;
        int tmpHistoryTokens = 0;
        int tmpUserTokens = 0;
        for (Map<String, Object> msg : messages) {
            String content = (String) msg.get("content");
            if (content == null)
                continue;
            String role = (String) msg.get("role");
            int tokens = estimateTokenCount(content);

            if ("system".equals(role)) {
                tmpSystemTokens += tokens;
            } else if (!"user".equals(role)) {
                tmpHistoryTokens += tokens;
            }
        }
        tmpUserTokens = estimateTokenCount(question);
        systemTokens = tmpSystemTokens;
        historyTokens = tmpHistoryTokens;
        userTokens = tmpUserTokens;
        final int totalTokens = systemTokens + historyTokens + userTokens;
        final int historyTurns = history != null ? (history.size() / 2) : 0;

        logger.fine(() -> "ask() CONTEXT METRICS — systemTokens=" + systemTokens
                + " historyTokens=" + historyTokens
                + " userTokens=" + userTokens
                + " totalTokens=" + totalTokens
                + " historyTurns=" + historyTurns
                + " conversationId=" + conversationId);

        logger.fine(() -> "ask() messages prepared — messageCount=" + messages.size()
                + " conversationId=" + conversationId);
        OpenRouterResponseDto response = aiResponseService.callOpenRouterAndExtract(messages, cityId);

        // Merge and de-duplicate sources from both procedure and event context
        // Procedure sources take precedence if there are duplicates (stable ordering by
        // relevance)
        List<Source> mergedSources = new ArrayList<>();
        if (procCtx != null && !procCtx.getSources().isEmpty()) {
            mergedSources.addAll(procedureContextService.deDuplicateAndOrderSources(procCtx.getSources()));
        }
        if (eventCtx != null && !eventCtx.getSources().isEmpty()) {
            mergedSources.addAll(eventCtx.getSources());
        }

        if (response.isSuccess() && !mergedSources.isEmpty()) {
            response = new OpenRouterResponseDto(
                    response.isSuccess(),
                    response.getMessage(),
                    response.getError(),
                    response.getStatusCode(),
                    response.getErrorCode(),
                    response.getPdfData(),
                    procedureContextService.deDuplicateAndOrderSources(mergedSources));
        }

        if (conversationId != null && !conversationId.isBlank() && response.getMessage() != null) {
            conversationService.updateConversationHistory(conversationId, question, response.getMessage());
        }
        return response;
    }

    @Override
    public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId,
            ComplainantIdentity identity, String cityId) {
        int inputLength = complaint != null ? complaint.length() : 0;
        boolean identityProvided = identity != null && identity.isPartiallyProvided();
        logger.info(() -> "redactComplaint() called — conversationId=" + conversationId
                + " inputLength=" + inputLength + " format=" + format + " identityProvided=" + identityProvided
                + " city=" + cityId);

        var validationError = validationService.validateRedactInput(complaint);
        if (validationError.isPresent()) {
            logger.fine(() -> "redactComplaint() rejected — reason=" + validationError.get().getError()
                    + " conversationId=" + conversationId);
            return validationError.get();
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId)));

        // Only add procedure context if we have complete identity (ready to draft)
        // Skip RAG search during identity collection to improve latency
        String contextBlock;
        boolean hasCompleteIdentity = identity != null && identity.isComplete();
        if (hasCompleteIdentity) {
            contextBlock = promptBuilder.buildProcedureContextBlock(complaint, cityId);
            if (contextBlock != null) {
                messages.add(Map.of("role", "system", "content", contextBlock));
            }
        }
        // Add conversation history
        var history = conversationService.getConversationHistory(conversationId);
        conversationService.addToMessages(messages, history);

        // Choose the prompt based on whether we have a complete identity.
        // When identity is incomplete, the AI is instructed to ask for the missing
        // fields first.
        // The caller is expected to collect the AI's question, present it to the user,
        // and
        // re-submit with the full identity in a subsequent request.
        String userPrompt = "";
        final boolean identityComplete = identity != null && identity.isComplete();
        if (identityComplete) {
            // On the second turn the user's message contains the identity data they typed
            // (e.g.
            // "My name is Raul Torres, DNI 49872354C, I live at Carrer Major 10"). The
            // original
            // complaint was saved on the first turn. We combine both so that any optional
            // contact
            // details the user included in their identity reply (address, phone, etc.) are
            // visible
            // to the prompt and can be included in the letter.
            String originalComplaint = conversationService.getPendingComplaint(conversationId);
            if (originalComplaint != null) {
                conversationService.clearPendingComplaint(conversationId);
                logger.fine(() -> "redactComplaint() resumed stored complaint — conversationId=" + conversationId
                        + " originalLength=" + originalComplaint.length());
            }
            String complaintForPrompt = (originalComplaint != null)
                    ? originalComplaint + "\n\n" + complaint
                    : complaint;
            if (complaintForPrompt != null) {
                userPrompt = promptBuilder.buildRedactPromptWithIdentity(complaintForPrompt, identity, cityId);
            }
        } else {
            if (conversationId != null && !conversationId.isBlank()) {
                conversationService.storePendingComplaint(conversationId, complaint);
                logger.fine(() -> "redactComplaint() saved complaint for identity follow-up — conversationId="
                        + conversationId);
            }
            userPrompt = promptBuilder.buildRedactPromptRequestingIdentity(complaint, identity, cityId);
        }

        messages.add(Map.of("role", "user", "content", userPrompt));

        logger.fine(() -> "redactComplaint() messages prepared — messageCount=" + messages.size()
                + " identityComplete=" + identityComplete + " conversationId=" + conversationId);
        OpenRouterResponseDto aiDto = aiResponseService.callOpenRouterAndExtract(messages, cityId);

        // Process the AI response using the dedicated service
        OpenRouterResponseDto processedResponse = aiResponseService.processComplaintResponse(aiDto, identityComplete);

        // Update conversation history if we have a valid response
        if (conversationId != null && !conversationId.isBlank() && processedResponse.getMessage() != null) {
            conversationService.updateConversationHistory(conversationId, userPrompt, processedResponse.getMessage());
        }

        return processedResponse;
    }

}
