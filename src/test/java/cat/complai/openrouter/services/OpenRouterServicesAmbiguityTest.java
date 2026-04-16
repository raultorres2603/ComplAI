package cat.complai.openrouter.services;

import cat.complai.http.HttpWrapper;
import cat.complai.openrouter.dto.AskStreamResult;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.openrouter.services.ai.AiResponseProcessingService;
import cat.complai.openrouter.services.conversation.ConversationManagementService;
import cat.complai.openrouter.services.procedure.ProcedureContextService;
import cat.complai.openrouter.services.validation.InputValidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenRouterServicesAmbiguityTest {

    private OpenRouterServices service;

    @Mock
    private InputValidationService validationService;

    @Mock
    private ConversationManagementService conversationService;

    @Mock
    private AiResponseProcessingService aiResponseService;

    @Mock
    private ProcedureContextService procedureContextService;

    @Mock
    private RedactPromptBuilder promptBuilder;

    @Mock
    private HttpWrapper httpWrapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OpenRouterServices(
                validationService,
                conversationService,
                aiResponseService,
                procedureContextService,
                promptBuilder,
                httpWrapper,
                new ObjectMapper());

        when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
        when(procedureContextService.requiresEventDateWindowClarification(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.deDuplicateAndOrderSources(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(promptBuilder.getSystemMessage(anyString(), anyString())).thenReturn("system");
        when(conversationService.getConversationHistory(any())).thenReturn(List.of());
        when(aiResponseService.callOpenRouterAndExtract(anyList(), anyString(), anyLong(), anyLong()))
                .thenReturn(new OpenRouterResponseDto(true, "ok", null, 200, OpenRouterErrorCode.NONE));
    }

    @Test
    void ask_ambiguousQuery_returnsClarificationAndStoresCandidates() {
        List<ConversationManagementService.ClarificationCandidate> candidates = List.of(
                new ConversationManagementService.ClarificationCandidate("proc-1", "Llicència d'obres menors"),
                new ConversationManagementService.ClarificationCandidate("proc-2", "Llicència d'obres majors"));
        ProcedureContextService.ProcedureAmbiguityResult ambiguityResult =
                new ProcedureContextService.ProcedureAmbiguityResult(candidates);

        when(procedureContextService.detectProcedureAmbiguity(anyString(), anyString()))
                .thenReturn(Optional.of(ambiguityResult));
        when(promptBuilder.buildProcedureClarificationMessage(anyList(), anyString(), anyString()))
                .thenReturn("Trobo 2 opcions: 1) Llicència d'obres menors 2) Llicència d'obres majors");

        OpenRouterResponseDto response = service.ask("llicència obres", "conv-1", "elprat");

        assertTrue(response.isSuccess());
        assertFalse(response.getMessage().isBlank());
        verify(conversationService).storePendingClarification(eq("conv-1"), eq(candidates));
        verify(conversationService).updateConversationHistory(eq("conv-1"), anyString(), anyString());
        verify(aiResponseService, never()).callOpenRouterAndExtract(anyList(), anyString(), anyLong(), anyLong());
    }

    @Test
    void ask_ambiguousQuery_noConversationId_skipsStorageButStillChecksAmbiguity() {
        when(procedureContextService.detectProcedureAmbiguity(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(procedureContextService.detectContextRequirements(anyString(), anyString()))
                .thenReturn(ProcedureContextService.ContextRequirements.none());

        OpenRouterResponseDto response = service.ask("llicència obres", null, "elprat");

        assertTrue(response.isSuccess());
        verify(conversationService, never()).storePendingClarification(anyString(), anyList());
        verify(aiResponseService).callOpenRouterAndExtract(anyList(), anyString(), anyLong(), anyLong());
    }

    @Test
    void streamAsk_ambiguousQuery_returnsFallbackStreamWithClarification() {
        List<ConversationManagementService.ClarificationCandidate> candidates = List.of(
                new ConversationManagementService.ClarificationCandidate("proc-1", "Llicència d'obres menors"),
                new ConversationManagementService.ClarificationCandidate("proc-2", "Llicència d'obres majors"));
        ProcedureContextService.ProcedureAmbiguityResult ambiguityResult =
                new ProcedureContextService.ProcedureAmbiguityResult(candidates);

        when(procedureContextService.detectProcedureAmbiguity(anyString(), anyString()))
                .thenReturn(Optional.of(ambiguityResult));
        when(promptBuilder.buildProcedureClarificationMessage(anyList(), anyString(), anyString()))
                .thenReturn("Trobo 2 opcions: 1) Llicència d'obres menors 2) Llicència d'obres majors");

        AskStreamResult result = service.streamAsk("llicència obres", "conv-1", "elprat");

        assertInstanceOf(AskStreamResult.Success.class, result);
        verify(conversationService).storePendingClarification(eq("conv-1"), eq(candidates));
    }

    @Test
    void ask_pendingClarification_resolvedByNumber_usesResolvedProcedure() {
        List<ConversationManagementService.ClarificationCandidate> pending = List.of(
                new ConversationManagementService.ClarificationCandidate("proc-A", "Llicència d'obres menors"),
                new ConversationManagementService.ClarificationCandidate("proc-B", "Llicència d'obres majors"));

        when(conversationService.getPendingClarification(eq("conv-1"))).thenReturn(pending);
        when(procedureContextService.buildProcedureContextResultForId(anyString(), anyString()))
                .thenReturn(new ProcedureContextService.ProcedureContextResult(null, List.of()));

        OpenRouterResponseDto response = service.ask("1", "conv-1", "elprat");

        assertTrue(response.isSuccess());
        verify(conversationService).clearPendingClarification(eq("conv-1"));
        verify(procedureContextService).buildProcedureContextResultForId(eq("proc-A"), eq("elprat"));
        verify(aiResponseService).callOpenRouterAndExtract(anyList(), anyString(), anyLong(), anyLong());
    }

    @Test
    void ask_pendingClarification_resolvedByTitle_usesResolvedProcedure() {
        List<ConversationManagementService.ClarificationCandidate> pending = List.of(
                new ConversationManagementService.ClarificationCandidate("proc-A", "Llicència d'obres menors"),
                new ConversationManagementService.ClarificationCandidate("proc-B", "Llicència d'obres majors"));

        when(conversationService.getPendingClarification(eq("conv-1"))).thenReturn(pending);
        when(procedureContextService.buildProcedureContextResultForId(anyString(), anyString()))
                .thenReturn(new ProcedureContextService.ProcedureContextResult(null, List.of()));

        OpenRouterResponseDto response = service.ask("vull la llicència d'obres majors", "conv-1", "elprat");

        assertTrue(response.isSuccess());
        verify(conversationService).clearPendingClarification(eq("conv-1"));
        verify(procedureContextService).buildProcedureContextResultForId(eq("proc-B"), eq("elprat"));
        verify(aiResponseService).callOpenRouterAndExtract(anyList(), anyString(), anyLong(), anyLong());
    }

    @Test
    void ask_pendingClarification_unresolvable_fallsBackToNormalRag() {
        List<ConversationManagementService.ClarificationCandidate> pending = List.of(
                new ConversationManagementService.ClarificationCandidate("proc-A", "Llicència d'obres menors"),
                new ConversationManagementService.ClarificationCandidate("proc-B", "Llicència d'obres majors"));

        when(conversationService.getPendingClarification(eq("conv-1"))).thenReturn(pending);
        when(procedureContextService.detectProcedureAmbiguity(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(procedureContextService.detectContextRequirements(anyString(), anyString()))
                .thenReturn(ProcedureContextService.ContextRequirements.none());

        OpenRouterResponseDto response = service.ask("banana", "conv-1", "elprat");

        assertTrue(response.isSuccess());
        verify(conversationService).clearPendingClarification(eq("conv-1"));
        verify(procedureContextService, never()).buildProcedureContextResultForId(anyString(), anyString());
        verify(aiResponseService).callOpenRouterAndExtract(anyList(), anyString(), anyLong(), anyLong());
    }

    @Test
    void ask_pendingClarification_cleared_beforeAiCall() {
        List<ConversationManagementService.ClarificationCandidate> pending = List.of(
                new ConversationManagementService.ClarificationCandidate("proc-A", "Llicència d'obres menors"));

        when(conversationService.getPendingClarification(eq("conv-1"))).thenReturn(pending);
        when(procedureContextService.buildProcedureContextResultForId(anyString(), anyString()))
                .thenReturn(new ProcedureContextService.ProcedureContextResult(null, List.of()));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(conversationService, aiResponseService);

        service.ask("1", "conv-1", "elprat");

        inOrder.verify(conversationService).clearPendingClarification(eq("conv-1"));
        inOrder.verify(aiResponseService).callOpenRouterAndExtract(anyList(), anyString(), anyLong(), anyLong());
    }
}
