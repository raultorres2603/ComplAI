package cat.complai.openrouter.services;

import cat.complai.http.HttpWrapper;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.Source;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenRouterServicesOrchestrationTest {

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
        when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("system");
        when(conversationService.getConversationHistory(any())).thenReturn(List.of());
        when(aiResponseService.callOpenRouterAndExtract(anyList(), eq("elprat"), any(Long.class), any(Long.class)))
                .thenReturn(new OpenRouterResponseDto(true, "ok", null, 200, OpenRouterErrorCode.NONE));
    }

    @Test
    void ask_noContextNeeded_skipsAllRagBuilders() {
        when(procedureContextService.detectContextRequirements(anyString(), eq("elprat")))
                .thenReturn(ProcedureContextService.ContextRequirements.none());

        OpenRouterResponseDto response = service.ask("hello", null, "elprat");

        assertTrue(response.isSuccess());
        verify(procedureContextService, never()).buildProcedureContextResult(anyString(), anyString());
        verify(procedureContextService, never()).buildEventContextResult(anyString(), anyString());
        verify(procedureContextService, never()).buildProcedureContextResultAsync(anyString(), anyString(),
                any(Executor.class));
        verify(procedureContextService, never()).buildEventContextResultAsync(anyString(), anyString(),
                any(Executor.class));
        verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), eq(0L));
    }

    @Test
    void ask_procedureOnly_usesSynchronousProcedurePath() {
        ProcedureContextService.ProcedureContextResult procedureContext = new ProcedureContextService.ProcedureContextResult(
                "procedure-context",
                List.of(new Source("https://example.com/procedure", "Procedure")));
        when(procedureContextService.detectContextRequirements(anyString(), eq("elprat")))
                .thenReturn(new ProcedureContextService.ContextRequirements(true, false));
        when(procedureContextService.buildProcedureContextResult(anyString(), eq("elprat")))
                .thenReturn(procedureContext);

        OpenRouterResponseDto response = service.ask("procedure question", null, "elprat");

        assertTrue(response.isSuccess());
        verify(procedureContextService).buildProcedureContextResult(anyString(), eq("elprat"));
        verify(procedureContextService, never()).buildEventContextResult(anyString(), anyString());
        verify(procedureContextService, never()).buildProcedureContextResultAsync(anyString(), anyString(),
                any(Executor.class));
        verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), any(Long.class), eq(0L));
    }

    @Test
    void ask_eventOnly_usesSynchronousEventPath() {
        ProcedureContextService.EventContextResult eventContext = new ProcedureContextService.EventContextResult(
                "event-context",
                List.of(new Source("https://example.com/event", "Event")));
        when(procedureContextService.detectContextRequirements(anyString(), eq("elprat")))
                .thenReturn(new ProcedureContextService.ContextRequirements(false, true));
        when(procedureContextService.buildEventContextResult(anyString(), eq("elprat")))
                .thenReturn(eventContext);

        OpenRouterResponseDto response = service.ask("event question", null, "elprat");

        assertTrue(response.isSuccess());
        verify(procedureContextService).buildEventContextResult(anyString(), eq("elprat"));
        verify(procedureContextService, never()).buildProcedureContextResult(anyString(), anyString());
        verify(procedureContextService, never()).buildEventContextResultAsync(anyString(), anyString(),
                any(Executor.class));
        verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), any(Long.class));
    }

    @Test
    void ask_bothContexts_usesBoundedParallelAsyncPath() {
        ProcedureContextService.ProcedureContextResult procedureContext = new ProcedureContextService.ProcedureContextResult(
                "procedure-context",
                List.of(new Source("https://example.com/procedure", "Procedure")));
        ProcedureContextService.EventContextResult eventContext = new ProcedureContextService.EventContextResult(
                "event-context",
                List.of(new Source("https://example.com/event", "Event")));
        when(procedureContextService.detectContextRequirements(anyString(), eq("elprat")))
                .thenReturn(new ProcedureContextService.ContextRequirements(true, true));
        when(procedureContextService.buildProcedureContextResultAsync(anyString(), eq("elprat"), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(procedureContext));
        when(procedureContextService.buildEventContextResultAsync(anyString(), eq("elprat"), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(eventContext));

        OpenRouterResponseDto response = service.ask("combined question", null, "elprat");

        assertTrue(response.isSuccess());
        verify(procedureContextService).buildProcedureContextResultAsync(anyString(), eq("elprat"),
                any(Executor.class));
        verify(procedureContextService).buildEventContextResultAsync(anyString(), eq("elprat"), any(Executor.class));
        verify(procedureContextService, never()).buildProcedureContextResult(anyString(), anyString());
        verify(procedureContextService, never()).buildEventContextResult(anyString(), anyString());
    }

    @Test
    void ask_contextBuilderFailure_keepsPartialContextInsteadOfFailingRequest() {
        ProcedureContextService.EventContextResult eventContext = new ProcedureContextService.EventContextResult(
                "event-context",
                List.of(new Source("https://example.com/event", "Event")));
        when(procedureContextService.detectContextRequirements(anyString(), eq("elprat")))
                .thenReturn(new ProcedureContextService.ContextRequirements(true, true));
        when(procedureContextService.buildProcedureContextResultAsync(anyString(), eq("elprat"), any(Executor.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("procedure failed")));
        when(procedureContextService.buildEventContextResultAsync(anyString(), eq("elprat"), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(eventContext));

        OpenRouterResponseDto response = service.ask("combined question", null, "elprat");

        assertTrue(response.isSuccess());
        verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), any(Long.class));
    }
}