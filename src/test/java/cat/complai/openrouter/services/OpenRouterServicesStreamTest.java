package cat.complai.openrouter.services;

import cat.complai.http.HttpWrapper;
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
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
                objectMapper
        );
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
                new Source("http://example.com/event1", "Event 1")
        );
        ProcedureContextResult procCtx = new ProcedureContextResult("Procedure context", sources);

        when(procedureContextService.questionNeedsProcedureContext(anyString(), anyString()))
                .thenReturn(true);
        when(procedureContextService.questionNeedsEventContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.buildProcedureContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(procCtx));
        when(procedureContextService.buildEventContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Mock streaming chunks from OpenRouter
        String chunk1 = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}";
        String chunk2 = "data: {\"choices\":[{\"delta\":{\"content\":\"World\"}}]}";
        String doneChunk = "data: [DONE]";

        when(httpWrapper.streamFromOpenRouter(anyList()))
                .thenReturn(Flux.just(chunk1, chunk2, doneChunk));

        // Execute and collect
        List<String> emitted = reactor.core.publisher.Flux.from(service.streamAsk("What is a recycling center?", "conv-123", "elprat"))
                .collectList()
                .block();

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

        when(procedureContextService.questionNeedsProcedureContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.questionNeedsEventContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.buildProcedureContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(procedureContextService.buildEventContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        String chunk1 = "data: {\"choices\":[{\"delta\":{\"content\":\"Response\"}}]}";
        String doneChunk = "data: [DONE]";

        when(httpWrapper.streamFromOpenRouter(anyList()))
                .thenReturn(Flux.just(chunk1, doneChunk));

        // Execute
        List<String> emitted = reactor.core.publisher.Flux.from(service.streamAsk("Question?", null, "elprat"))
                .collectList()
                .block();

        // Verify sources event has empty array
        assertEquals(3, emitted.size(), "Should emit 3 events: chunk, sources, done");
        SseSourcesEvent sourcesEvent = objectMapper.readValue(emitted.get(1), SseSourcesEvent.class);
        assertEquals("sources", sourcesEvent.type());
        assertTrue(sourcesEvent.sources().isEmpty());
    }

    @Test
    void streamAsk_OnValidationError_EmitsErrorEvent() throws Exception {
        // Setup validation error
        OpenRouterResponseDto validationError = new OpenRouterResponseDto(false, null, "Invalid question", null,
                OpenRouterErrorCode.VALIDATION);
        when(validationService.validateQuestion(anyString())).thenReturn(Optional.of(validationError));

        // Execute
        List<String> emitted = reactor.core.publisher.Flux.from(service.streamAsk("", null, "elprat"))
                .collectList()
                .block();

        // Verify error event
        assertEquals(1, emitted.size());
        SseErrorEvent errorEvent = objectMapper.readValue(emitted.get(0), SseErrorEvent.class);
        assertEquals("error", errorEvent.type());
        assertEquals("Invalid question", errorEvent.error());
        assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), (int) errorEvent.errorCode());
    }

    @Test
    void streamAsk_OnStreamError_ConvertsExceptionToErrorEvent() throws Exception {
        // Setup
        when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
        when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("System");
        when(conversationService.getConversationHistory(anyString())).thenReturn(List.of());

        when(procedureContextService.questionNeedsProcedureContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.questionNeedsEventContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.buildProcedureContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(procedureContextService.buildEventContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        // Mock stream that fails
        String chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"Test\"}}]}";
        when(httpWrapper.streamFromOpenRouter(anyList()))
                .thenReturn(Flux.just(chunk)
                        .concatWith(Flux.error(new RuntimeException("OpenRouter connection failed"))));

        // Execute
        List<String> emitted = reactor.core.publisher.Flux.from(service.streamAsk("Question?", null, "elprat"))
                .collectList()
                .block();

        // Verify error event was emitted
        assertEquals(2, emitted.size()); // chunk + error event
        SseErrorEvent errorEvent = objectMapper.readValue(emitted.get(1), SseErrorEvent.class);
        assertEquals("error", errorEvent.type());
        assertTrue(errorEvent.error().contains("OpenRouter connection failed"));
        assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), (int) errorEvent.errorCode());
    }

    @Test
    void streamAsk_ConversationIdIncludedInDoneEvent() throws Exception {
        // Setup
        when(validationService.validateQuestion(anyString())).thenReturn(Optional.empty());
        when(promptBuilder.getSystemMessage(eq("elprat"), anyString())).thenReturn("System");
        when(conversationService.getConversationHistory(anyString())).thenReturn(List.of());

        when(procedureContextService.questionNeedsProcedureContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.questionNeedsEventContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.buildProcedureContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(procedureContextService.buildEventContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        String chunk = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"}}]}";
        String done = "data: [DONE]";

        when(httpWrapper.streamFromOpenRouter(anyList()))
                .thenReturn(Flux.just(chunk, done));

        // Execute
        List<String> emitted = reactor.core.publisher.Flux.from(service.streamAsk("Q?", "conv-abc-123", "elprat"))
                .collectList()
                .block();

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

        when(procedureContextService.questionNeedsProcedureContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.questionNeedsEventContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.buildProcedureContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(procedureContextService.buildEventContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        when(httpWrapper.streamFromOpenRouter(anyList()))
                .thenReturn(Flux.just("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}", "data: [DONE]"));

        // Execute
        List<String> emitted = reactor.core.publisher.Flux.from(service.streamAsk("Q", null, "elprat"))
                .collectList()
                .block();

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

        when(procedureContextService.questionNeedsProcedureContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.questionNeedsEventContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.buildProcedureContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(procedureContextService.buildEventContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        when(httpWrapper.streamFromOpenRouter(anyList()))
                .thenReturn(Flux.just(
                        ": OPENROUTER PROCESSING",
                        "",
                        "   ",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}",
                        "data: [DONE]"));

        List<String> emitted = reactor.core.publisher.Flux.from(service.streamAsk("Question?", null, "elprat"))
                .collectList()
                .block();

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

        when(procedureContextService.questionNeedsProcedureContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.questionNeedsEventContext(anyString(), anyString()))
                .thenReturn(false);
        when(procedureContextService.buildProcedureContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(procedureContextService.buildEventContextResultAsync(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        when(httpWrapper.streamFromOpenRouter(anyList()))
                .thenReturn(Flux.just(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Start\"}}]}",
                        "data: {not-json"));

        List<String> emitted = reactor.core.publisher.Flux.from(service.streamAsk("Question?", null, "elprat"))
                .collectList()
                .block();

        assertEquals(2, emitted.size(), "Should emit first chunk and then mapped error event");
        SseErrorEvent errorEvent = objectMapper.readValue(emitted.get(1), SseErrorEvent.class);
        assertEquals("error", errorEvent.type());
        assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), (int) errorEvent.errorCode());
        assertTrue(errorEvent.error().toLowerCase().contains("malformed upstream stream"));
    }
}
