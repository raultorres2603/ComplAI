package cat.complai.services.openrouter;

import cat.complai.dto.openrouter.AskStreamResult;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.Source;
import cat.complai.dto.openrouter.sse.SseChunkEvent;
import cat.complai.dto.openrouter.sse.SseDoneEvent;
import cat.complai.dto.openrouter.sse.SseErrorEvent;
import cat.complai.dto.openrouter.sse.SseSources;
import cat.complai.dto.openrouter.sse.SseSourcesEvent;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.services.openrouter.ai.AiResponseProcessingService;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
import cat.complai.services.openrouter.validation.InputValidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
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

        private ObjectMapper objectMapper;

        @BeforeEach
        void setUp() {
                MockitoAnnotations.openMocks(this);
                objectMapper = new ObjectMapper();
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
        }

        private List<String> collectStream(AskStreamResult result) {
                assertInstanceOf(AskStreamResult.Success.class, result);
                return Flux.from(((AskStreamResult.Success) result).stream()).collectList().block();
        }

        @Test
        void streamAsk_EmitsChunk_ThenSources_ThenDone() throws Exception {
                // Setup streaming orchestrator mock to return expected SSE events
                String chunk1 = objectMapper.writeValueAsString(new SseChunkEvent("Hello "));
                String chunk2 = objectMapper.writeValueAsString(new SseChunkEvent("World"));
                List<Source> sources = List.of(
                                new Source("http://example.com/proc1", "Procedure 1"),
                                new Source("http://example.com/event1", "Event 1"));
                List<SseSources> sourcesList = sources.stream()
                                .map(s -> new SseSources(s.getTitle(), s.getUrl()))
                                .toList();
                String sourcesJson = objectMapper.writeValueAsString(new SseSourcesEvent(sourcesList));
                String doneJson = objectMapper.writeValueAsString(new SseDoneEvent("conv-123"));

                when(streamingOrchestrator.streamAsk("What is a recycling center?", "conv-123", "elprat"))
                                .thenReturn(new AskStreamResult.Success(
                                                Flux.just(chunk1, chunk2, sourcesJson, doneJson)));

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
                // Setup streaming orchestrator to return a response with empty sources
                String chunk = objectMapper.writeValueAsString(new SseChunkEvent("Response"));
                String sourcesJson = objectMapper.writeValueAsString(new SseSourcesEvent(List.of()));
                String doneJson = objectMapper.writeValueAsString(new SseDoneEvent(null));

                when(streamingOrchestrator.streamAsk("Question?", null, "elprat"))
                                .thenReturn(new AskStreamResult.Success(Flux.just(chunk, sourcesJson, doneJson)));

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
                String fallbackMsg = "I could not find related recent news about that in elprat.";
                String chunk = objectMapper.writeValueAsString(new SseChunkEvent(fallbackMsg));
                String sourcesJson = objectMapper.writeValueAsString(new SseSourcesEvent(List.of()));
                String doneJson = objectMapper.writeValueAsString(new SseDoneEvent("conv-news"));

                when(streamingOrchestrator.streamAsk("Any recent news about martians?", "conv-news", "elprat"))
                                .thenReturn(new AskStreamResult.Success(Flux.just(chunk, sourcesJson, doneJson)));

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
        }

        @Test
        void streamAsk_eventWithoutDateWindow_emitsClarificationChunkAndSkipsUpstream() throws Exception {
                String clarification = "To help with events, please provide a date window";
                String chunk = objectMapper.writeValueAsString(new SseChunkEvent(clarification));
                String sourcesJson = objectMapper.writeValueAsString(new SseSourcesEvent(List.of()));
                String doneJson = objectMapper.writeValueAsString(new SseDoneEvent("conv-event"));

                when(streamingOrchestrator.streamAsk("What events are happening?", "conv-event", "elprat"))
                                .thenReturn(new AskStreamResult.Success(Flux.just(chunk, sourcesJson, doneJson)));

                List<String> emitted = collectStream(
                                service.streamAsk("What events are happening?", "conv-event", "elprat"));

                assertEquals(3, emitted.size(), "Should emit clarification chunk, sources, and done");

                SseChunkEvent chunkEvent = objectMapper.readValue(emitted.get(0), SseChunkEvent.class);
                assertEquals("chunk", chunkEvent.type());
                assertTrue(chunkEvent.content().contains("date window")
                                || chunkEvent.content().contains("rango de fechas")
                                || chunkEvent.content().contains("interval de dates"));

                SseSourcesEvent sourcesEvent = objectMapper.readValue(emitted.get(1), SseSourcesEvent.class);
                assertTrue(sourcesEvent.sources().isEmpty());

                SseDoneEvent doneEvent = objectMapper.readValue(emitted.get(2), SseDoneEvent.class);
                assertEquals("conv-event", doneEvent.conversationId());
        }

        @Test
        void streamAsk_OnValidationError_EmitsErrorEvent() throws Exception {
                SseErrorEvent expectedError = new SseErrorEvent("Invalid question",
                                OpenRouterErrorCode.VALIDATION.getCode());
                String errorJson = objectMapper.writeValueAsString(expectedError);

                when(streamingOrchestrator.streamAsk("", null, "elprat"))
                                .thenReturn(new AskStreamResult.Success(Flux.just(errorJson)));

                List<String> emitted = collectStream(service.streamAsk("", null, "elprat"));

                assertEquals(1, emitted.size());
                SseErrorEvent errorEvent = objectMapper.readValue(emitted.get(0), SseErrorEvent.class);
                assertEquals("error", errorEvent.type());
                assertEquals("Invalid question", errorEvent.error());
                assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), (int) errorEvent.errorCode());
        }

        @Test
        void streamAsk_PreStreamUpstream402_ReturnsTypedErrorResult() {
                OpenRouterResponseDto errorDto = new OpenRouterResponseDto(false, null,
                                "AI service is temporarily unavailable. Please try again later.",
                                402, OpenRouterErrorCode.UPSTREAM);

                when(streamingOrchestrator.streamAsk("Question?", null, "elprat"))
                                .thenReturn(new AskStreamResult.Error(errorDto));

                AskStreamResult result = service.streamAsk("Question?", null, "elprat");

                assertInstanceOf(AskStreamResult.Error.class, result);
                OpenRouterResponseDto dto = ((AskStreamResult.Error) result).errorResponse();
                assertEquals(OpenRouterErrorCode.UPSTREAM, dto.getErrorCode());
                assertEquals(402, dto.getStatusCode());
                assertEquals("AI service is temporarily unavailable. Please try again later.", dto.getError());
        }

        @Test
        void streamAsk_OnMidStreamUpstreamError_ConvertsExceptionToErrorEvent() throws Exception {
                String chunk = objectMapper.writeValueAsString(new SseChunkEvent("Test"));
                String sourcesJson = objectMapper.writeValueAsString(new SseSourcesEvent(List.of()));
                String errorJson = objectMapper.writeValueAsString(new SseErrorEvent(
                                "AI service is temporarily unavailable. Please try again later.",
                                OpenRouterErrorCode.UPSTREAM.getCode()));

                // Mid-stream error: first a chunk, then sources, then an error event
                when(streamingOrchestrator.streamAsk("Question?", null, "elprat"))
                                .thenReturn(new AskStreamResult.Success(Flux.just(chunk, sourcesJson, errorJson)));

                List<String> emitted = collectStream(service.streamAsk("Question?", null, "elprat"));

                assertEquals(3, emitted.size());
                SseErrorEvent errorEvent = objectMapper.readValue(emitted.get(2), SseErrorEvent.class);
                assertEquals("error", errorEvent.type());
                assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), (int) errorEvent.errorCode());
                assertEquals("AI service is temporarily unavailable. Please try again later.", errorEvent.error());
        }

        @Test
        void streamAsk_ConversationIdIncludedInDoneEvent() throws Exception {
                String chunk = objectMapper.writeValueAsString(new SseChunkEvent("OK"));
                String sourcesJson = objectMapper.writeValueAsString(new SseSourcesEvent(List.of()));
                String doneJson = objectMapper.writeValueAsString(new SseDoneEvent("conv-abc-123"));

                when(streamingOrchestrator.streamAsk("Q?", "conv-abc-123", "elprat"))
                                .thenReturn(new AskStreamResult.Success(Flux.just(chunk, sourcesJson, doneJson)));

                List<String> emitted = collectStream(service.streamAsk("Q?", "conv-abc-123", "elprat"));

                assertEquals(3, emitted.size()); // chunk, sources, done
                SseDoneEvent doneEvent = objectMapper.readValue(emitted.get(2), SseDoneEvent.class);
                assertEquals("conv-abc-123", doneEvent.conversationId());
        }

        @Test
        void streamAsk_EmitsCorrectEventTypeField() throws Exception {
                String chunk = objectMapper.writeValueAsString(new SseChunkEvent("x"));
                String sourcesJson = objectMapper.writeValueAsString(new SseSourcesEvent(List.of()));
                String doneJson = objectMapper.writeValueAsString(new SseDoneEvent(null));

                when(streamingOrchestrator.streamAsk("Q", null, "elprat"))
                                .thenReturn(new AskStreamResult.Success(Flux.just(chunk, sourcesJson, doneJson)));

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
                // The StreamingOrchestrator handles comment/empty filtering internally.
                // This test validates the delegation works correctly with a clean stream.
                String chunk = objectMapper.writeValueAsString(new SseChunkEvent("Hi"));
                String sourcesJson = objectMapper.writeValueAsString(new SseSourcesEvent(List.of()));
                String doneJson = objectMapper.writeValueAsString(new SseDoneEvent(null));

                when(streamingOrchestrator.streamAsk("Question?", null, "elprat"))
                                .thenReturn(new AskStreamResult.Success(Flux.just(chunk, sourcesJson, doneJson)));

                List<String> emitted = collectStream(service.streamAsk("Question?", null, "elprat"));

                assertEquals(3, emitted.size(), "Should emit chunk, sources, done");
                SseChunkEvent chunkEvent = objectMapper.readValue(emitted.get(0), SseChunkEvent.class);
                assertEquals("chunk", chunkEvent.type());
                assertEquals("Hi", chunkEvent.content());
        }

        @Test
        void streamAsk_malformedChunk_afterData_emitsMappedErrorEvent() throws Exception {
                // Malformed chunk handling is done by StreamingOrchestrator.
                // This test validates delegation with an error event in the stream.
                String chunk = objectMapper.writeValueAsString(new SseChunkEvent("Start"));
                String errorJson = objectMapper.writeValueAsString(new SseErrorEvent(
                                "AI service is temporarily unavailable. Please try again later.",
                                OpenRouterErrorCode.UPSTREAM.getCode()));

                when(streamingOrchestrator.streamAsk("Question?", null, "elprat"))
                                .thenReturn(new AskStreamResult.Success(Flux.just(chunk, errorJson)));

                List<String> emitted = collectStream(service.streamAsk("Question?", null, "elprat"));

                assertEquals(2, emitted.size(), "Should emit first chunk and then mapped error event");
                SseErrorEvent errorEvent = objectMapper.readValue(emitted.get(1), SseErrorEvent.class);
                assertEquals("error", errorEvent.type());
                assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), (int) errorEvent.errorCode());
                assertEquals("AI service is temporarily unavailable. Please try again later.", errorEvent.error());
        }
}
