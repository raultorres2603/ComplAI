package cat.complai.openrouter.interfaces.services;

import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cat.complai.openrouter.dto.OutputFormat;

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

        OpenRouterResponseDto out = svc.redactComplaint("   ", OutputFormat.JSON);
        assertFalse(out.isSuccess());
        assertEquals("Complaint must not be empty.", out.getError());
    }

    @Test
    void redact_pdfRequested_returnsPdfMagicBytes() {
        String aiMessage = "{\"format\": \"pdf\"}\n\nDear Ajuntament of El Prat,\n\nI am writing to complain about...\n\nSincerely,\nResident";
        FakeSuccessWrapper wrapper = new FakeSuccessWrapper(aiMessage);
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text", OutputFormat.PDF);
        assertTrue(out.isSuccess());
        assertNotNull(out.getPdfData());
        String head = new String(out.getPdfData(), 0, Math.min(4, out.getPdfData().length), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF"), "PDF magic bytes expected at start of file");
    }

    @Test
    void redact_auto_shouldProducePdf_whenAiReturnsFormalLetter() {
        // AI returns a long, formal letter which should trigger the AUTO->PDF heuristic
        StringBuilder sb = new StringBuilder();
        sb.append("Dear Ajuntament of El Prat,\n\n");
        for (int i = 0; i < 30; i++) {
            sb.append("I am writing to express my concern about noise pollution near my residence. This has been ongoing and affects quality of life. ");
        }
        sb.append("\n\nSincerely,\nA concerned resident");
        String aiMessage = sb.toString();

        // prepend required JSON header
        aiMessage = "{\"format\": \"pdf\"}\n\n" + aiMessage;
        FakeSuccessWrapper wrapper = new FakeSuccessWrapper(aiMessage);
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        OpenRouterResponseDto out = svc.redactComplaint("Some long complaint text", OutputFormat.AUTO);
        assertTrue(out.isSuccess(), "Service should report success");
        assertNotNull(out.getPdfData(), "PDF data should be produced for AUTO when AI message is a formal letter");
        String head = new String(out.getPdfData(), 0, Math.min(4, out.getPdfData().length), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF"), "PDF magic bytes expected at start of file");
    }
}
