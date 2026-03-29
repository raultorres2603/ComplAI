package cat.complai.openrouter.services;

import cat.complai.http.HttpWrapper;
import cat.complai.http.OpenRouterStreamingException;
import cat.complai.http.dto.OpenRouterStreamStartResult;
import cat.complai.openrouter.dto.AskStreamResult;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.Source;
import cat.complai.openrouter.dto.sse.SseChunkEvent;
import cat.complai.openrouter.dto.sse.SseDoneEvent;
import cat.complai.openrouter.dto.sse.SseErrorEvent;
import cat.complai.openrouter.dto.sse.SseSourcesEvent;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.openrouter.services.ai.AiResponseProcessingService;
import cat.complai.openrouter.services.conversation.ConversationManagementService;
import cat.complai.openrouter.services.procedure.ProcedureContextService;
import cat.complai.openrouter.services.procedure.ProcedureContextService.ProcedureContextResult;
import cat.complai.openrouter.services.validation.InputValidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenRouterServicesStreamTest {

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

        private ObjectMapper objectMapper;

        @BeforeEach
        void setUp() {
                MockitoAnnotations.openMocks(this);
                objectMapper = new ObjectMapper();
                service = new OpenRouterServices(
                                validationService,
                                conversationService,
                                aiResponseService,
                                procedureContextService,
                                promptBuilder,
                                httpWrapper,
                                objectMapper);
        }

        private List<String> collectStream(AskStreamResult result) {
                assertInstanceOf(AskStreamResult.Success.class, result);
                return Flux.from(((AskStreamResult.Success) result).stream()).collectList().block();
        }

        @Test
        void streamAsk_EmitsChunk_ThenSources_ThenDone() throws Exception {
                // Setup
                when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("System");
                when(conversationService.getConversationHistory(anyString())).thenReturn(List.of());

                // Setup RAG with sources
                List<Source> sources = List.of(
                                new Source("http://example.com/proc1", "Procedure 1"),
                                new Source("http://example.com/event1", "Event 1"));
                ProcedureContextResult procCtx = new ProcedureContextResult("Procedure context", sources);

                when(procedureContextService.detectContextRequirements(anyString(), anyString()))
                                .thenReturn(new ProcedureContextService.ContextRequirements(true, false, false));
                when(procedureContextService.buildProcedureContextResult(anyString(), anyString()))
                                .thenReturn(procCtx);

                // Mock streaming chunks from OpenRouter
                String chunk1 = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}";
                String chunk2 = "data: {\"choices\":[{\"delta\":{\"content\":\"World\"}}]}";
                String doneChunk = "data: [DONE]";

                when(httpWrapper.streamFromOpenRouter(anyList()))
                                .thenReturn(new OpenRouterStreamStartResult.Success(
                                                Flux.just(chunk1, chunk2, doneChunk)));

                // Execute and collect
                List<String> emitted = collectStream(
                                service.streamAsk("What is a recycling center?", "conv-123", "elprat"));

                // Verify sequence
                assertEquals(4, emitted.size(), "Should emit 4 events: chunk1, chunk2, sources, done");

                // Parse and verify events
                SseChunkEvent chunk1Event = objectMapper.readValue(emitted.get(0), SseChunkEvent.class);
                assertEquals("chunk", chunk1Event.type());
                assertEquals("Hello ", chunk1Event.content());

                SseChunkEvent chunk2Event = objectMapper.readValue(emitted.get(1), SseChunkEvent.class);
                assertEquals("chunk", chunk2Event.type());
                assertEquals("World", chunk2Event.content());

                SseSourcesEvent sourcesEvent = objectMapper.readValue(emitted.get(2), SseSourcesEvent.class);
                assertEquals("sources", sourcesEvent.type());
                assertEquals(2, sourcesEvent.sources().size());
                assertEquals("Procedure 1", sourcesEvent.sources().get(0).title());
                assertEquals("Event 1", sourcesEvent.sources().get(1).title());

                SseDoneEvent doneEvent = objectMapper.readValue(emitted.get(3), SseDoneEvent.class);
                assertEquals("done", doneEvent.type());
                assertEquals("conv-123", doneEvent.conversationId());
        }

        @Test
        void streamAsk_WithNoSources_EmitsEmptySourcesEvent() throws Exception {
                // Setup
                when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("System");
                when(conversationService.getConversationHistory(anyString())).thenReturn(List.of());

                when(procedureContextService.detectContextRequirements(anyString(), anyString()))
                                .thenReturn(ProcedureContextService.ContextRequirements.none());

                String chunk1 = "data: {\"choices\":[{\"delta\":{\"content\":\"Response\"}}]}";
                String doneChunk = "data: [DONE]";

                when(httpWrapper.streamFromOpenRouter(anyList()))
                                .thenReturn(new OpenRouterStreamStartResult.Success(Flux.just(chunk1, doneChunk)));

                // Execute
                List<String> emitted = collectStream(service.streamAsk("Question?", null, "elprat"));

                // Verify sources event has empty array
                assertEquals(3, emitted.size(), "Should emit 3 events: chunk, sources, done");
                SseSourcesEvent sourcesEvent = objectMapper.readValue(emitted.get(1), SseSourcesEvent.class);
                assertEquals("sources", sourcesEvent.type());
                assertTrue(sourcesEvent.sources().isEmpty());
        }

        @Test
        void streamAsk_newsIntentWithoutMatches_emitsFallbackChunkSourcesDone() throws Exception {
                when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("System");
                when(procedureContextService.detectContextRequirements(anyString(), anyString()))
                                .thenReturn(new ProcedureContextService.ContextRequirements(false, false, true));
                when(procedureContextService.buildNewsContextResult(anyString(), anyString()))
                                .thenReturn(new ProcedureContextService.NewsContextResult(null, List.of()));

                List<String> emitted = collectStream(
                                service.streamAsk("Any recent news about martians?", "conv-news", "elprat"));

                assertEquals(3, emitted.size(), "Should emit fallback chunk, sources, and done");

                SseChunkEvent chunkEvent = objectMapper.readValue(emitted.get(0), SseChunkEvent.class);
                assertEquals("chunk", chunkEvent.type());
                assertTrue(chunkEvent.content().contains("I could not find related recent news"));

                SseSourcesEvent sourcesEvent = objectMapper.readValue(emitted.get(1), SseSourcesEvent.class);
                assertTrue(sourcesEvent.sources().isEmpty());

                SseDoneEvent doneEvent = objectMapper.readValue(emitted.get(2), SseDoneEvent.class);
                assertEquals("conv-news", doneEvent.conversationId());

                verify(httpWrapper, never()).streamFromOpenRouter(anyList());
        }

        @Test
        void streamAsk_OnValidationError_EmitsErrorEvent() throws Exception {
                // Setup validation error
                OpenRouterResponseDto validationError = new OpenRouterResponseDto(false, null, "Invalid question", null,
                                OpenRouterErrorCode.VALIDATION);
                when(validationService.validateQuestion(anyString())).thenReturn(Optional.of(validationError));

                // Execute
                List<String> emitted = collectStream(service.streamAsk("", null, "elprat"));

                // Verify error event
                assertEquals(1, emitted.size());
                SseErrorEvent errorEvent = objectMapper.readValue(emitted.get(0), SseErrorEvent.class);
                assertEquals("error", errorEvent.type());
                assertEquals("Invalid question", errorEvent.error());
                assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), (int) errorEvent.errorCode());
        }

        @Test
        void streamAsk_PreStreamUpstream402_ReturnsTypedErrorResult() {
                // Setup
                when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("System");
                when(conversationService.getConversationHistory(anyString())).thenReturn(List.of());

                when(procedureContextService.detectContextRequirements(anyString(), anyString()))
                                .thenReturn(ProcedureContextService.ContextRequirements.none());

                when(httpWrapper.streamFromOpenRouter(anyList()))
                                .thenReturn(new OpenRouterStreamStartResult.Error(new OpenRouterStreamingException(
                                                OpenRouterErrorCode.UPSTREAM,
                                                "OpenRouter stream startup failed.",
                                                402)));

                AskStreamResult result = service.streamAsk("Question?", null, "elprat");

                assertInstanceOf(AskStreamResult.Error.class, result);
                OpenRouterResponseDto dto = ((AskStreamResult.Error) result).errorResponse();
                assertEquals(OpenRouterErrorCode.UPSTREAM, dto.getErrorCode());
                assertEquals(402, dto.getStatusCode());
                assertEquals("AI service is temporarily unavailable. Please try again later.", dto.getError());
        }

        @Test
        void streamAsk_OnMidStreamUpstreamError_ConvertsExceptionToErrorEvent() throws Exception {
                when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("System");
                when(conversationService.getConversationHistory(anyString())).thenReturn(List.of());

                when(procedureContextService.detectContextRequirements(anyString(), anyString()))
                                .thenReturn(ProcedureContextService.ContextRequirements.none());

                String chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"Test\"}}]}";
                when(httpWrapper.streamFromOpenRouter(anyList()))
                                .thenReturn(new OpenRouterStreamStartResult.Success(Flux.just(chunk)
                                                .concatWith(Flux.error(new OpenRouterStreamingException(
                                                                OpenRouterErrorCode.UPSTREAM,
                                                                "OpenRouter streaming failed.",
                                                                null)))));

                List<String> emitted = collectStream(service.streamAsk("Question?", null, "elprat"));

                assertEquals(2, emitted.size());
                SseErrorEvent errorEvent = objectMapper.readValue(emitted.get(1), SseErrorEvent.class);
                assertEquals("error", errorEvent.type());
                assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), (int) errorEvent.errorCode());
                assertEquals("AI service is temporarily unavailable. Please try again later.", errorEvent.error());
        }

        @Test
        void streamAsk_ConversationIdIncludedInDoneEvent() throws Exception {
                // Setup
                when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("System");
                when(conversationService.getConversationHistory(anyString())).thenReturn(List.of());

                when(procedureContextService.detectContextRequirements(anyString(), anyString()))
                                .thenReturn(ProcedureContextService.ContextRequirements.none());

                String chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}";
                String done = "data: [DONE]";

                when(httpWrapper.streamFromOpenRouter(anyList()))
                                .thenReturn(new OpenRouterStreamStartResult.Success(Flux.just(chunk, done)));

                // Execute
                List<String> emitted = collectStream(service.streamAsk("Q?", "conv-abc-123", "elprat"));

                // Verify done event includes conversationId
                assertEquals(3, emitted.size()); // chunk, sources, done
                SseDoneEvent doneEvent = objectMapper.readValue(emitted.get(2), SseDoneEvent.class);
                assertEquals("conv-abc-123", doneEvent.conversationId());
        }

        @Test
        void streamAsk_EmitsCorrectEventTypeField() throws Exception {
                // Setup minimal scenario
                when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("System");
                when(conversationService.getConversationHistory(anyString())).thenReturn(List.of());

                when(procedureContextService.detectContextRequirements(anyString(), anyString()))
                                .thenReturn(ProcedureContextService.ContextRequirements.none());

                when(httpWrapper.streamFromOpenRouter(anyList()))
                                .thenReturn(new OpenRouterStreamStartResult.Success(
                                                Flux.just("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}",
                                                                "data: [DONE]")));

                // Execute
                List<String> emitted = collectStream(service.streamAsk("Q", null, "elprat"));

                // Verify all events have correct type field
                for (String eventJson : emitted) {
                        assertFalse(eventJson.isEmpty());
                        // Minimal check: each should contain "type" field
                        assertTrue(eventJson.contains("\"type\":"));
                }
        }

        @Test
        void streamAsk_ignoresCommentAndEmptyChunks() throws Exception {
                when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("System");
                when(conversationService.getConversationHistory(anyString())).thenReturn(List.of());

                when(procedureContextService.detectContextRequirements(anyString(), anyString()))
                                .thenReturn(ProcedureContextService.ContextRequirements.none());

                when(httpWrapper.streamFromOpenRouter(anyList()))
                                .thenReturn(new OpenRouterStreamStartResult.Success(Flux.just(
                                                ": OPENROUTER PROCESSING",
                                                "",
                                                "   ",
                                                "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}",
                                                "data: [DONE]")));

                List<String> emitted = collectStream(service.streamAsk("Question?", null, "elprat"));

                assertEquals(3, emitted.size(), "Should emit chunk, sources, done");
                SseChunkEvent chunkEvent = objectMapper.readValue(emitted.get(0), SseChunkEvent.class);
                assertEquals("chunk", chunkEvent.type());
                assertEquals("Hi", chunkEvent.content());
        }

        @Test
        void streamAsk_malformedChunk_afterData_emitsMappedErrorEvent() throws Exception {
                when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
                when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("System");
                when(conversationService.getConversationHistory(anyString())).thenReturn(List.of());

                when(procedureContextService.detectContextRequirements(anyString(), anyString()))
                                .thenReturn(ProcedureContextService.ContextRequirements.none());

                when(httpWrapper.streamFromOpenRouter(anyList()))
                                .thenReturn(new OpenRouterStreamStartResult.Success(Flux.just(
                                                "data: {\"choices\":[{\"delta\":{\"content\":\"Start\"}}]}",
                                                "data: {not-json")));

                List<String> emitted = collectStream(service.streamAsk("Question?", null, "elprat"));

                assertEquals(2, emitted.size(), "Should emit first chunk and then mapped error event");
                SseErrorEvent errorEvent = objectMapper.readValue(emitted.get(1), SseErrorEvent.class);
                assertEquals("error", errorEvent.type());
                assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), (int) errorEvent.errorCode());
                assertEquals("AI service is temporarily unavailable. Please try again later.", errorEvent.error());
        }
}
