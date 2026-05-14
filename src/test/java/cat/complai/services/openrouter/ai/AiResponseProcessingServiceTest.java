package cat.complai.services.openrouter.ai;

import cat.complai.utilities.http.HttpWrapper;
import cat.complai.dto.http.HttpDto;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.services.openrouter.cache.ResponseCacheService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class AiResponseProcessingServiceTest {

    static class FakeHttpWrapper extends HttpWrapper {
        int callCount = 0;
        CompletableFuture<HttpDto> nextResponse;

        void setNextResponse(HttpDto dto) {
            this.nextResponse = CompletableFuture.completedFuture(dto);
        }

        void setNextFuture(CompletableFuture<HttpDto> future) {
            this.nextResponse = future;
        }

        @Override
        public CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages) {
            callCount++;
            if (nextResponse != null) {
                CompletableFuture<HttpDto> resp = nextResponse;
                nextResponse = null;
                return resp;
            }
            return CompletableFuture.completedFuture(new HttpDto("default response", 200, "POST", null));
        }
    }

    private static ResponseCacheService createCache() {
        return new ResponseCacheService(true, 10, 500);
    }

    private static List<Map<String, Object>> makeMessages(String userText) {
        return List.of(Map.of("role", "user", "content", userText));
    }

    // --- Constructor ---

    @Test
    void constructor_negativeTimeout_defaultsTo30() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), -1);
        wrapper.setNextResponse(new HttpDto("ok", 200, "POST", null));
        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertTrue(result.isSuccess());
    }

    @Test
    void constructor_zeroTimeout_defaultsTo30() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 0);
        wrapper.setNextResponse(new HttpDto("ok", 200, "POST", null));
        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertTrue(result.isSuccess());
    }

    @Test
    void constructor_positiveTimeout_usedAsIs() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 45);
        wrapper.setNextResponse(new HttpDto("ok", 200, "POST", null));
        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertTrue(result.isSuccess());
    }

    // --- Deprecated method delegation ---

    @Test
    void deprecatedMethod_delegatesToNewOverload() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        wrapper.setNextResponse(new HttpDto("hello", 200, "POST", null));
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat");
        assertTrue(result.isSuccess());
        assertEquals(1, wrapper.callCount);
    }

    // --- Cache hit ---

    @Test
    void callOpenRouter_cacheHit_doesNotCallHttpWrapper() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("cached value", 200, "POST", null));

        OpenRouterResponseDto first = svc.callOpenRouterAndExtract(makeMessages("cache test"), "elprat", 0, 0);
        assertTrue(first.isSuccess());
        assertEquals(1, wrapper.callCount);

        wrapper.setNextResponse(new HttpDto("should not be used", 200, "POST", null));
        OpenRouterResponseDto second = svc.callOpenRouterAndExtract(makeMessages("cache test"), "elprat", 0, 0);
        assertTrue(second.isSuccess());
        assertTrue(second.getMessage().contains("cached value"), "Cached response should contain the original text");
        assertEquals(1, wrapper.callCount);
    }

    @Test
    void callOpenRouter_cacheMiss_callsHttpWrapper() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("fresh response", 200, "POST", null));

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("unique"), "elprat", 0, 0);
        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("fresh response"));
        assertEquals(1, wrapper.callCount);
    }

    // --- Error responses ---

    @Test
    void callOpenRouter_nullHttpWrapperResponse_returnsUpstreamError() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(null);

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertFalse(result.isSuccess());
        assertEquals(OpenRouterErrorCode.UPSTREAM, result.getErrorCode());
        assertEquals("No response from AI service.", result.getError());
    }

    @Test
    void callOpenRouter_httpErrorResponse_returnsUpstreamErrorWithMessage() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto(null, 500, "POST", "Internal Server Error"));

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertFalse(result.isSuccess());
        assertEquals(OpenRouterErrorCode.UPSTREAM, result.getErrorCode());
        assertEquals("Internal Server Error", result.getError());
    }

    // --- AI refusal ---

    @Test
    void callOpenRouter_aiRefusesEnglish_returnsRefusal() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("I cannot help with that request.", 200, "POST", null));

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertFalse(result.isSuccess());
        assertEquals(OpenRouterErrorCode.REFUSAL, result.getErrorCode());
        assertEquals("Request is not about El Prat de Llobregat.", result.getError());
    }

    @Test
    void callOpenRouter_aiRefusesCatalan_returnsRefusal() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("No puc ajudar amb això.", 200, "POST", null));

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertFalse(result.isSuccess());
        assertEquals(OpenRouterErrorCode.REFUSAL, result.getErrorCode());
    }

    @Test
    void callOpenRouter_aiRefusesCityScope_returnsRefusal() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("I only answer questions about El Prat de Llobregat.", 200, "POST", null));

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertFalse(result.isSuccess());
        assertEquals(OpenRouterErrorCode.REFUSAL, result.getErrorCode());
    }

    @Test
    void callOpenRouter_normalCityDiscussion_noRefusal() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("El Prat de Llobregat has a great recycling program.", 200, "POST", null));

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertTrue(result.isSuccess());
        assertEquals(OpenRouterErrorCode.NONE, result.getErrorCode());
    }

    // --- Empty message ---

    @Test
    void callOpenRouter_emptyMessage_returnsUpstream() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("", 200, "POST", null));

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertFalse(result.isSuccess());
        assertEquals(OpenRouterErrorCode.UPSTREAM, result.getErrorCode());
        assertEquals("AI returned no message.", result.getError());
    }

    // --- Timeout ---

    @Test
    void callOpenRouter_timeout_returnsTimeoutError() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 1);
        CompletableFuture<HttpDto> neverCompletes = new CompletableFuture<>();
        wrapper.setNextFuture(neverCompletes);

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertFalse(result.isSuccess());
        assertEquals(OpenRouterErrorCode.TIMEOUT, result.getErrorCode());
        assertEquals("AI service timed out.", result.getError());
    }

    // --- Generic exception ---

    @Test
    void callOpenRouter_genericException_returnsInternalError() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        CompletableFuture<HttpDto> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("connection refused"));
        wrapper.setNextFuture(failedFuture);

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertFalse(result.isSuccess());
        assertEquals(OpenRouterErrorCode.INTERNAL, result.getErrorCode());
        assertTrue(result.getError().contains("connection refused"), "Error should contain the original exception message");
    }

    // --- Successful response ---

    @Test
    void callOpenRouter_success_returnsHtmlFormattedMessage() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("Hello from El Prat AI", 200, "POST", null));

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(makeMessages("test"), "elprat", 0, 0);
        assertTrue(result.isSuccess());
        assertEquals("<p>Hello from El Prat AI</p>", result.getMessage());
        assertEquals(OpenRouterErrorCode.NONE, result.getErrorCode());
    }

    // --- processComplaintResponse ---

    @Test
    void processComplaintResponse_nonNoneErrorCode_propagates() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        OpenRouterResponseDto input = new OpenRouterResponseDto(false, null, "some error", 500, OpenRouterErrorCode.UPSTREAM);
        OpenRouterResponseDto result = svc.processComplaintResponse(input, true);
        assertSame(input, result);
    }

    @Test
    void processComplaintResponse_identityIncomplete_returnsCleanedMessage() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        OpenRouterResponseDto input = new OpenRouterResponseDto(true, "What is your name?", null, 200, OpenRouterErrorCode.NONE);
        OpenRouterResponseDto result = svc.processComplaintResponse(input, false);
        assertTrue(result.isSuccess());
        assertEquals(OpenRouterErrorCode.NONE, result.getErrorCode());
        assertTrue(result.getMessage().contains("What is your name?"));
    }

    @Test
    void processComplaintResponse_noFormatHeader_returnsRawMessage() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        OpenRouterResponseDto input = new OpenRouterResponseDto(true, "Raw letter body without header", null, 200, OpenRouterErrorCode.NONE);
        OpenRouterResponseDto result = svc.processComplaintResponse(input, true);
        assertTrue(result.isSuccess());
        assertEquals(OpenRouterErrorCode.NONE, result.getErrorCode());
        assertTrue(result.getMessage().contains("Raw letter body without header"));
    }

    @Test
    void processComplaintResponse_hasFormatHeader_returnsExtractedBody() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        String aiMsg = "{\"format\": \"pdf\"}\n\nDear Ajuntament,\n\nComplaint letter body...\n\nSincerely,\nResident";
        OpenRouterResponseDto input = new OpenRouterResponseDto(true, aiMsg, null, 200, OpenRouterErrorCode.NONE);
        OpenRouterResponseDto result = svc.processComplaintResponse(input, true);
        assertTrue(result.isSuccess());
        assertEquals(OpenRouterErrorCode.NONE, result.getErrorCode());
        assertTrue(result.getMessage().contains("Complaint letter body"));
    }

    @Test
    void processComplaintResponse_nullMessage_safe() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        OpenRouterResponseDto input = new OpenRouterResponseDto(true, null, null, 200, OpenRouterErrorCode.NONE);
        OpenRouterResponseDto result = svc.processComplaintResponse(input, true);
        assertTrue(result.isSuccess());
        assertEquals(OpenRouterErrorCode.NONE, result.getErrorCode());
        assertNull(result.getMessage());
    }

    // --- hashQuestion (indirect via cache) ---

    @Test
    void hashQuestion_sameQuestionDifferentCase_matchesCache() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("library info", 200, "POST", null));

        svc.callOpenRouterAndExtract(makeMessages("Where is the library?"), "elprat", 0, 0);
        assertEquals(1, wrapper.callCount);

        wrapper.setNextResponse(new HttpDto("should be cached", 200, "POST", null));
        OpenRouterResponseDto second = svc.callOpenRouterAndExtract(makeMessages("WHERE IS THE LIBRARY?"), "elprat", 0, 0);
        assertTrue(second.isSuccess());
        assertTrue(second.getMessage().contains("library info"));
        assertEquals(1, wrapper.callCount);
    }

    @Test
    void hashQuestion_blankAndWhitespace_matchCacheKey() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("blank response", 200, "POST", null));

        svc.callOpenRouterAndExtract(makeMessages(""), "elprat", 0, 0);
        assertEquals(1, wrapper.callCount);

        wrapper.setNextResponse(new HttpDto("should be cached", 200, "POST", null));
        OpenRouterResponseDto second = svc.callOpenRouterAndExtract(makeMessages("   "), "elprat", 0, 0);
        assertTrue(second.isSuccess());
        assertTrue(second.getMessage().contains("blank response"));
        assertEquals(1, wrapper.callCount);
    }

    // --- extractUserQuestion (indirect via category detection in cache key) ---

    @Test
    void extractUserQuestion_returnsLastUserMessage() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("response", 200, "POST", null));

        List<Map<String, Object>> messages = List.of(
            Map.of("role", "assistant", "content", "What can I help with?"),
            Map.of("role", "user", "content", "Where is the library?"),
            Map.of("role", "assistant", "content", "The library is at Main St."),
            Map.of("role", "user", "content", "What about parking?")
        );
        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(messages, "elprat", 0, 0);
        assertTrue(result.isSuccess());
        assertEquals(1, wrapper.callCount);

        // Second call with same last user message should hit cache
        wrapper.setNextResponse(new HttpDto("should be cached", 200, "POST", null));
        OpenRouterResponseDto second = svc.callOpenRouterAndExtract(messages, "elprat", 0, 0);
        assertTrue(second.isSuccess());
        assertEquals(1, wrapper.callCount);
    }

    @Test
    void extractUserQuestion_emptyMessages_doesNotThrow() {
        FakeHttpWrapper wrapper = new FakeHttpWrapper();
        AiResponseProcessingService svc = new AiResponseProcessingService(wrapper, createCache(), 30);
        wrapper.setNextResponse(new HttpDto("response", 200, "POST", null));

        OpenRouterResponseDto result = svc.callOpenRouterAndExtract(List.of(), "elprat", 0, 0);
        assertTrue(result.isSuccess());
    }
}
