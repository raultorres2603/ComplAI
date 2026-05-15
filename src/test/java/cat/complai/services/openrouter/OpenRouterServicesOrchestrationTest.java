package cat.complai.services.openrouter;

import cat.complai.utilities.http.HttpWrapper;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.Source;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.services.openrouter.ai.AiResponseProcessingService;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
import cat.complai.services.openrouter.validation.InputValidationService;
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
import static org.mockito.Mockito.doAnswer;
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
        private IntentDetector intentDetector;

        @Mock
        private RagContextBuilder ragContextBuilder;

        @Mock
        private ClarificationService clarificationService;

        @Mock
        private StreamingOrchestrator streamingOrchestrator;

        @Mock
        private RedactOrchestrator redactOrchestrator;

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
                                intentDetector,
                                ragContextBuilder,
                                clarificationService,
                                streamingOrchestrator,
                                redactOrchestrator,
                                promptBuilder);

                when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
                when(intentDetector.requiresEventDateWindowClarification(anyString(), eq("elprat")))
                                .thenReturn(false);
                when(clarificationService.detectProcedureAmbiguity(anyString(), anyString()))
                                .thenReturn(Optional.empty());
                when(ragContextBuilder.deDuplicateAndOrderSources(anyList()))
                                .thenAnswer(invocation -> invocation.getArgument(0));
                doAnswer(invocation -> {
                        List<Source> merged = invocation.getArgument(0);
                        List<Source> contextSources = invocation.getArgument(1);
                        if (contextSources != null) {
                                merged.addAll(contextSources);
                        }
                        return null;
                }).when(ragContextBuilder).validateAndAddSources(anyList(), anyList(), anyString());
                when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("system");
                when(conversationService.getConversationHistory(any())).thenReturn(List.of());
                when(aiResponseService.callOpenRouterAndExtract(anyList(), eq("elprat"), any(Long.class),
                                any(Long.class)))
                                .thenReturn(new OpenRouterResponseDto(true, "ok", null, 200, OpenRouterErrorCode.NONE));
        }

        @Test
        void ask_noContextNeeded_skipsAllRagBuilders() {
                when(intentDetector.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(ContextRequirements.none());

                OpenRouterResponseDto response = service.ask("hello", null, "elprat");

                assertTrue(response.isSuccess());
                verify(ragContextBuilder, never()).buildProcedureContextResult(anyString(), anyString());
                verify(ragContextBuilder, never()).buildEventContextResult(anyString(), anyString());
                verify(ragContextBuilder, never()).buildProcedureContextResultAsync(anyString(), anyString(),
                                any(Executor.class));
                verify(ragContextBuilder, never()).buildEventContextResultAsync(anyString(), anyString(),
                                any(Executor.class));
                verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), eq(0L));
        }

        @Test
        void ask_procedureOnly_usesSynchronousProcedurePath() {
                ProcedureContextResult procedureContext = new ProcedureContextResult(
                                "procedure-context",
                                List.of(new Source("https://example.com/procedure", "Procedure")));
                when(intentDetector.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ContextRequirements(true, false, false, false));
                when(ragContextBuilder.buildProcedureContextResult(anyString(), eq("elprat")))
                                .thenReturn(procedureContext);

                OpenRouterResponseDto response = service.ask("procedure question", null, "elprat");

                assertTrue(response.isSuccess());
                assertFalse(response.getSources().isEmpty());
                assertEquals("https://example.com/procedure", response.getSources().getFirst().getUrl());
                verify(ragContextBuilder).buildProcedureContextResult(anyString(), eq("elprat"));
                verify(ragContextBuilder, never()).buildEventContextResult(anyString(), anyString());
                verify(ragContextBuilder, never()).buildProcedureContextResultAsync(anyString(), anyString(),
                                any(Executor.class));
                verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), any(Long.class), eq(0L));
        }

        @Test
        void ask_eventOnly_usesSynchronousEventPath() {
                EventContextResult eventContext = new EventContextResult(
                                "event-context",
                                List.of(new Source("https://example.com/event", "Event")));
                when(intentDetector.requiresEventDateWindowClarification(anyString(), eq("elprat")))
                                .thenReturn(false);
                when(intentDetector.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ContextRequirements(false, true, false, false));
                when(ragContextBuilder.buildEventContextResult(anyString(), eq("elprat")))
                                .thenReturn(eventContext);

                OpenRouterResponseDto response = service.ask("event question", null, "elprat");

                assertTrue(response.isSuccess());
                assertFalse(response.getSources().isEmpty());
                assertEquals("https://example.com/event", response.getSources().getFirst().getUrl());
                verify(ragContextBuilder).buildEventContextResult(anyString(), eq("elprat"));
                verify(ragContextBuilder, never()).buildProcedureContextResult(anyString(), anyString());
                verify(ragContextBuilder, never()).buildEventContextResultAsync(anyString(), anyString(),
                                any(Executor.class));
                verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), any(Long.class));
        }

        @Test
        void ask_eventIntentWithoutDateWindow_returnsClarificationAndSkipsRetrieval() {
                when(intentDetector.requiresEventDateWindowClarification(anyString(), eq("elprat")))
                                .thenReturn(true);

                OpenRouterResponseDto response = service.ask("What events are happening?", "conv-1", "elprat");

                assertTrue(response.isSuccess());
                assertTrue(response.getMessage().contains("date window")
                                || response.getMessage().contains("rango de fechas")
                                || response.getMessage().contains("interval de dates"));
                verify(intentDetector, never()).detectContextRequirements(anyString(), anyString());
                verify(ragContextBuilder, never()).buildEventContextResult(anyString(), anyString());
                verify(aiResponseService, never()).callOpenRouterAndExtract(anyList(), anyString(), anyLong(),
                                anyLong());
        }

        @Test
        void ask_bothContexts_usesBoundedParallelAsyncPath() {
                ProcedureContextResult procedureContext = new ProcedureContextResult(
                                "procedure-context",
                                List.of(new Source("https://example.com/procedure", "Procedure")));
                EventContextResult eventContext = new EventContextResult(
                                "event-context",
                                List.of(new Source("https://example.com/event", "Event")));
                when(intentDetector.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ContextRequirements(true, true, false, false));
                when(ragContextBuilder.buildProcedureContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class)))
                                .thenReturn(CompletableFuture.completedFuture(procedureContext));
                when(ragContextBuilder.buildEventContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class)))
                                .thenReturn(CompletableFuture.completedFuture(eventContext));

                OpenRouterResponseDto response = service.ask("combined question", null, "elprat");

                assertTrue(response.isSuccess());
                verify(ragContextBuilder).buildProcedureContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class));
                verify(ragContextBuilder).buildEventContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class));
                verify(ragContextBuilder, never()).buildProcedureContextResult(anyString(), anyString());
                verify(ragContextBuilder, never()).buildEventContextResult(anyString(), anyString());
        }

        @Test
        void ask_contextBuilderFailure_keepsPartialContextInsteadOfFailingRequest() {
                EventContextResult eventContext = new EventContextResult(
                                "event-context",
                                List.of(new Source("https://example.com/event", "Event")));
                when(intentDetector.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ContextRequirements(true, true, false, false));
                when(ragContextBuilder.buildProcedureContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class)))
                                .thenReturn(CompletableFuture
                                                .failedFuture(new IllegalStateException("procedure failed")));
                when(ragContextBuilder.buildEventContextResultAsync(anyString(), eq("elprat"),
                                any(Executor.class)))
                                .thenReturn(CompletableFuture.completedFuture(eventContext));

                OpenRouterResponseDto response = service.ask("combined question", null, "elprat");

                assertTrue(response.isSuccess());
                verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), any(Long.class));
        }

        @Test
        void ask_newsIntentWithoutMatches_returnsDeterministicFallbackWithoutCallingAi() {
                when(intentDetector.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ContextRequirements(false, false, true, false));
                when(ragContextBuilder.buildNewsContextResult(anyString(), eq("elprat")))
                                .thenReturn(new NewsContextResult(null, List.of()));

                OpenRouterResponseDto response = service.ask("Any recent news about Martian taxes?", null, "elprat");

                assertTrue(response.isSuccess());
                assertTrue(response.getMessage().contains("I could not find related recent news"));
                verify(aiResponseService, never()).callOpenRouterAndExtract(anyList(), anyString(), anyLong(),
                                anyLong());
        }

        @Test
        void ask_newsIntentWithMatches_callsAiWithNewsContextHash() {
                NewsContextResult newsContext = new NewsContextResult(
                                "news-context",
                                List.of(new Source("https://example.com/news", "News")));
                when(intentDetector.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ContextRequirements(false, false, true, false));
                when(ragContextBuilder.buildNewsContextResult(anyString(), eq("elprat")))
                                .thenReturn(newsContext);

                OpenRouterResponseDto response = service.ask("Latest news about recycling", null, "elprat");

                assertTrue(response.isSuccess());
                assertFalse(response.getSources().isEmpty());
                assertEquals("https://example.com/news", response.getSources().getFirst().getUrl());
                verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), anyLong());
        }

        @Test
        void ask_cityInfoOnly_loadsCityInfoContextAndSources() {
                CityInfoContextResult cityInfoContext = new CityInfoContextResult(
                                "cityinfo-context",
                                List.of(new Source("https://example.com/cityinfo", "City Info")));
                when(intentDetector.detectContextRequirements(anyString(), eq("elprat")))
                                .thenReturn(new ContextRequirements(false, false, false, true));
                when(ragContextBuilder.buildCityInfoContextResult(anyString(), eq("elprat")))
                                .thenReturn(cityInfoContext);

                OpenRouterResponseDto response = service.ask("municipal information", null, "elprat");

                assertTrue(response.isSuccess());
                assertFalse(response.getSources().isEmpty());
                assertEquals("https://example.com/cityinfo", response.getSources().getFirst().getUrl());
                verify(ragContextBuilder).buildCityInfoContextResult(anyString(), eq("elprat"));
                verify(aiResponseService).callOpenRouterAndExtract(anyList(), eq("elprat"), eq(0L), anyLong());
        }
}