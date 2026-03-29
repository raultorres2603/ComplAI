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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
                when(procedureContextService.requiresEventDateWindowClarification(anyString(), eq("elprat")))
                                .thenReturn(false);
                when(procedureContextService.deDuplicateAndOrderSources(anyList()))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("system");
                when(conversationService.getConversationHistory(any())).thenReturn(List.of());
                when(aiResponseService.callOpenRouterAndExtract(anyList(), eq("elprat"), any(Long.class),
                                any(Long.class)))
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
                                .thenReturn(new ProcedureContextService.ContextRequirements(true, false, false, false));
                when(procedureContextService.buildProcedureContextResult(anyString(), eq("elprat")))
                                .thenReturn(procedureContext);

                OpenRouterResponseDto response = service.ask("procedure question", null, "elprat");

                assertTrue(response.isSuccess());
                assertFalse(response.getSources().isEmpty());
                assertEquals("https://example.com/procedure", response.getSources().getFirst().getUrl());
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
                when(procedureContextService.requiresEventDateWindowClarification(anyString(), eq("elprat")))
                                .thenReturn(false);
                when(procedureContextService.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ProcedureContextService.ContextRequirements(false, true, false, false));
                when(procedureContextService.buildEventContextResult(anyString(), eq("elprat")))
                                .thenReturn(eventContext);

                OpenRouterResponseDto response = service.ask("event question", null, "elprat");

                assertTrue(response.isSuccess());
                assertFalse(response.getSources().isEmpty());
                assertEquals("https://example.com/event", response.getSources().getFirst().getUrl());
                verify(procedureContextService).buildEventContextResult(anyString(), eq("elprat"));
                verify(procedureContextService, never()).buildProcedureContextResult(anyString(), anyString());
                verify(procedureContextService, never()).buildEventContextResultAsync(anyString(), anyString(),
                                any(Executor.class));
                verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), any(Long.class));
        }

        @Test
        void ask_eventIntentWithoutDateWindow_returnsClarificationAndSkipsRetrieval() {
                when(procedureContextService.requiresEventDateWindowClarification(anyString(), eq("elprat")))
                                .thenReturn(true);

                OpenRouterResponseDto response = service.ask("What events are happening?", "conv-1", "elprat");

                assertTrue(response.isSuccess());
                assertTrue(response.getMessage().contains("date window")
                                || response.getMessage().contains("rango de fechas")
                                || response.getMessage().contains("interval de dates"));
                verify(procedureContextService, never()).detectContextRequirements(anyString(), anyString());
                verify(procedureContextService, never()).buildEventContextResult(anyString(), anyString());
                verify(aiResponseService, never()).callOpenRouterAndExtract(anyList(), anyString(), anyLong(),
                                anyLong());
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
                                .thenReturn(new ProcedureContextService.ContextRequirements(true, true, false, false));
                when(procedureContextService.buildProcedureContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class)))
                                .thenReturn(CompletableFuture.completedFuture(procedureContext));
                when(procedureContextService.buildEventContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class)))
                                .thenReturn(CompletableFuture.completedFuture(eventContext));

                OpenRouterResponseDto response = service.ask("combined question", null, "elprat");

                assertTrue(response.isSuccess());
                verify(procedureContextService).buildProcedureContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class));
                verify(procedureContextService).buildEventContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class));
                verify(procedureContextService, never()).buildProcedureContextResult(anyString(), anyString());
                verify(procedureContextService, never()).buildEventContextResult(anyString(), anyString());
        }

        @Test
        void ask_contextBuilderFailure_keepsPartialContextInsteadOfFailingRequest() {
                ProcedureContextService.EventContextResult eventContext = new ProcedureContextService.EventContextResult(
                                "event-context",
                                List.of(new Source("https://example.com/event", "Event")));
                when(procedureContextService.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ProcedureContextService.ContextRequirements(true, true, false, false));
                when(procedureContextService.buildProcedureContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class)))
                                .thenReturn(CompletableFuture
                                                .failedFuture(new IllegalStateException("procedure failed")));
                when(procedureContextService.buildEventContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class)))
                                .thenReturn(CompletableFuture.completedFuture(eventContext));

                OpenRouterResponseDto response = service.ask("combined question", null, "elprat");

                assertTrue(response.isSuccess());
                verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), any(Long.class));
        }

        @Test
        void ask_newsIntentWithoutMatches_returnsDeterministicFallbackWithoutCallingAi() {
                when(procedureContextService.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ProcedureContextService.ContextRequirements(false, false, true, false));
                when(procedureContextService.buildNewsContextResult(anyString(), eq("elprat")))
                                .thenReturn(new ProcedureContextService.NewsContextResult(null, List.of()));

                OpenRouterResponseDto response = service.ask("Any recent news about Martian taxes?", null, "elprat");

                assertTrue(response.isSuccess());
                assertTrue(response.getMessage().contains("I could not find related recent news"));
                verify(aiResponseService, never()).callOpenRouterAndExtract(anyList(), anyString(), anyLong(),
                                anyLong());
        }

        @Test
        void ask_newsIntentWithMatches_callsAiWithNewsContextHash() {
                ProcedureContextService.NewsContextResult newsContext = new ProcedureContextService.NewsContextResult(
                                "news-context",
                                List.of(new Source("https://example.com/news", "News")));
                when(procedureContextService.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ProcedureContextService.ContextRequirements(false, false, true, false));
                when(procedureContextService.buildNewsContextResult(anyString(), eq("elprat")))
                                .thenReturn(newsContext);

                OpenRouterResponseDto response = service.ask("Latest news about recycling", null, "elprat");

                assertTrue(response.isSuccess());
                assertFalse(response.getSources().isEmpty());
                assertEquals("https://example.com/news", response.getSources().getFirst().getUrl());
                verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), anyLong());
        }

        @Test
        void ask_cityInfoOnly_loadsCityInfoContextAndSources() {
                ProcedureContextService.CityInfoContextResult cityInfoContext = new ProcedureContextService.CityInfoContextResult(
                                "cityinfo-context",
                                List.of(new Source("https://example.com/cityinfo", "City Info")));
                when(procedureContextService.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ProcedureContextService.ContextRequirements(false, false, false, true));
                when(procedureContextService.buildCityInfoContextResult(anyString(), eq("elprat")))
                                .thenReturn(cityInfoContext);

                OpenRouterResponseDto response = service.ask("municipal information", null, "elprat");

                assertTrue(response.isSuccess());
                assertFalse(response.getSources().isEmpty());
                assertEquals("https://example.com/cityinfo", response.getSources().getFirst().getUrl());
                verify(procedureContextService).buildCityInfoContextResult(anyString(), eq("elprat"));
                verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), anyLong());
        }
}