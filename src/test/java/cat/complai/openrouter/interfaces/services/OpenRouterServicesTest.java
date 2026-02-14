package cat.complai.openrouter.interfaces.services;

import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for OpenRouterServices. Uses a small fake HttpWrapper to avoid network calls.
 */
public class OpenRouterServicesTest {

    // Fake wrapper that returns a successful HttpDto with provided message
    static class FakeSuccessWrapper extends HttpWrapper {
        private final String message;

        FakeSuccessWrapper(String message) {
            super();
            this.message = message;
        }

        @Override
        public CompletableFuture<HttpDto> postToOpenRouterAsync(String userPrompt) {
            return CompletableFuture.completedFuture(new HttpDto(message, 200, "POST", null));
        }
    }

    // Fake wrapper that returns an error
    static class FakeErrorWrapper extends HttpWrapper {
        private final String error;

        FakeErrorWrapper(String error) {
            super();
            this.error = error;
        }

        @Override
        public CompletableFuture<HttpDto> postToOpenRouterAsync(String userPrompt) {
            return CompletableFuture.completedFuture(new HttpDto(null, 500, "POST", error));
        }
    }

    // Fake wrapper that simulates AI refusing because the request isn't about El Prat
    static class FakeRefuseWrapper extends HttpWrapper {
        FakeRefuseWrapper() { super(); }

        @Override
        public CompletableFuture<HttpDto> postToOpenRouterAsync(String userPrompt) {
            String aiReply = "I'm sorry, I can't help with that request because it's not about El Prat de Llobregat.";
            return CompletableFuture.completedFuture(new HttpDto(aiReply, 200, "POST", null));
        }
    }

    @Test
    void ask_happyPath_returnsAiMessage() {
        FakeSuccessWrapper wrapper = new FakeSuccessWrapper("Hello from El Prat AI");
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        String input = "Is there a recycling center in El Prat de Llobregat?";
        OpenRouterResponseDto out = svc.ask(input);
        assertTrue(out.isSuccess());
        assertEquals("Hello from El Prat AI", out.getMessage());
        assertEquals(null, out.getError());
    }

    @Test
    void ask_wrapperError_surfacesError() {
        FakeErrorWrapper wrapper = new FakeErrorWrapper("Missing OPENROUTER_API_KEY");
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        String input = "How to contact the Ajuntament of El Prat?";
        OpenRouterResponseDto out = svc.ask(input);
        assertFalse(out.isSuccess());
        assertEquals("Missing OPENROUTER_API_KEY", out.getError());
    }

    @Test
    void ask_aiRefuses_whenNotAboutElPrat() {
        FakeRefuseWrapper wrapper = new FakeRefuseWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        String input = "What is the capital of France?"; // not about El Prat
        OpenRouterResponseDto out = svc.ask(input);
        assertFalse(out.isSuccess());
        assertEquals("Request is not about El Prat de Llobregat.", out.getError());
    }

    @Test
    void redact_emptyComplaint_rejects() {
        FakeSuccessWrapper wrapper = new FakeSuccessWrapper("OK");
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        OpenRouterResponseDto out = svc.redactComplaint("   ");
        assertFalse(out.isSuccess());
        assertEquals("Complaint must not be empty.", out.getError());
    }
}
