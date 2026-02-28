package cat.complai.openrouter.interfaces.services;

import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.nio.charset.StandardCharsets;

import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OutputFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenRouterServices. Uses a small fake HttpWrapper to avoid network calls.
 */
public class OpenRouterServicesTest {

    // Flexible fake wrapper that simulates all test scenarios based on the last user message content.
    // The service always calls postToOpenRouterAsync(List) with the assembled message history,
    // so that is the overload we must override to intercept calls correctly.
    static class ScenarioFakeWrapper extends HttpWrapper {
        @Override
        public CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages) {
            // Extract the content of the last user message to determine which scenario to run.
            String userPrompt = messages == null ? null : messages.stream()
                    .filter(m -> "user".equals(m.get("role")))
                    .reduce((first, second) -> second)
                    .map(m -> (String) m.get("content"))
                    .orElse(null);

            if (userPrompt != null && userPrompt.contains("[REFUSE]")) {
                return CompletableFuture.completedFuture(new HttpDto("I'm sorry, I can't help with that request because it's not about El Prat de Llobregat.", 200, "POST", null));
            }
            if (userPrompt != null && userPrompt.contains("[UPSTREAM]")) {
                return CompletableFuture.completedFuture(new HttpDto(null, 500, "POST", "Upstream error"));
            }
            if (userPrompt != null && userPrompt.contains("[HEADER]")) {
                String body = "{\"format\": \"pdf\"}\n\nDear Ajuntament,\n\nI am writing to complain about noise...\n\nSincerely,\nResident";
                return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
            }
            if (userPrompt != null && userPrompt.contains("[NOHEADER]")) {
                String body = "Dear Ajuntament,\n\nI am writing to complain about...\n\nSincerely,\nResident";
                return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
            }
            if (userPrompt != null && userPrompt.contains("[HEADER_LONG]")) {
                StringBuilder sb = new StringBuilder();
                sb.append("{\"format\": \"pdf\"}\n\nDear Ajuntament,\n\n");
                sb.append("This is a long complaint sentence to generate many pages. ".repeat(800));
                sb.append("\n\nSincerely,\nResident");
                return CompletableFuture.completedFuture(new HttpDto(sb.toString(), 200, "POST", null));
            }
            if (userPrompt != null && userPrompt.contains("[HEADER_INVALID]")) {
                String body = "{\"format\": \"xml\"}\n\nThis body should be rejected due to invalid format.";
                return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
            }
            if (userPrompt != null && userPrompt.contains("recycling center")) {
                return CompletableFuture.completedFuture(new HttpDto("Hello from El Prat AI", 200, "POST", null));
            }
            // Fallback: simulate a generic successful response
            return CompletableFuture.completedFuture(new HttpDto("Dear Ajuntament,\n\nI am writing to complain about...\n\nSincerely,\nResident", 200, "POST", null));
        }
    }

    @Test
    void ask_happyPath_returnsAiMessage() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        String input = "Is there a recycling center in El Prat de Llobregat?";
        OpenRouterResponseDto out = svc.ask(input, null);
        assertTrue(out.isSuccess());
        assertEquals("Hello from El Prat AI", out.getMessage());
        assertNull(out.getError());
    }

    @Test
    void ask_wrapperError_surfacesError() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        String input = "How to contact the Ajuntament of El Prat? [UPSTREAM]";
        OpenRouterResponseDto out = svc.ask(input, null);
        assertFalse(out.isSuccess());
        assertEquals("Upstream error", out.getError());
    }

    @Test
    void ask_aiRefuses_whenNotAboutElPrat() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        String input = "What is the capital of France? [REFUSE]";
        OpenRouterResponseDto out = svc.ask(input, null);
        assertFalse(out.isSuccess());
        assertEquals("Request is not about El Prat de Llobregat.", out.getError());
    }

    @Test
    void redact_emptyComplaint_rejects() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        OpenRouterResponseDto out = svc.redactComplaint("   ", OutputFormat.JSON, null);
        assertFalse(out.isSuccess());
        assertEquals("Complaint must not be empty.", out.getError());
    }

    @Test
    void redact_pdfRequested_returnsPdfMagicBytes() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [HEADER]", OutputFormat.PDF, null);
        assertTrue(out.isSuccess());
        assertNotNull(out.getPdfData());
        String head = new String(out.getPdfData(), 0, Math.min(4, out.getPdfData().length), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF"), "PDF magic bytes expected at start of file");
    }

    @Test
    void redact_missingJsonHeader_withAutoFormat_fallsBackToJsonSuccess() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        String aiMessage = "Dear Ajuntament,\n\nI am writing to complain about...\n\nSincerely,\nResident";
        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [NOHEADER]", OutputFormat.AUTO, null);

        assertTrue(out.isSuccess(), "Missing header with AUTO must not fail the request");
        assertEquals(aiMessage, out.getMessage(), "Raw AI message must be returned as-is");
        assertNotNull(out.getMessage());
    }

    @Test
    void redact_missingJsonHeader_withJsonFormat_fallsBackToJsonSuccess() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        String aiMessage = "Dear Ajuntament,\n\nI am writing to complain about...\n\nSincerely,\nResident";
        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [NOHEADER]", OutputFormat.JSON, null);

        assertTrue(out.isSuccess(), "Missing header with JSON must not fail the request");
        assertEquals(aiMessage, out.getMessage());
    }

    @Test
    void redact_missingJsonHeader_withPdfFormat_returnsError() {
        // When the client explicitly asked for PDF but the AI omits the header we cannot extract
        // a clean letter body, so the service must return an error rather than produce a broken PDF.
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [NOHEADER]", OutputFormat.PDF, null);

        assertFalse(out.isSuccess(), "Missing header with explicit PDF must report failure");
        assertEquals(OpenRouterErrorCode.UPSTREAM, out.getErrorCode());
    }

    @Test
    void redact_auto_shouldProducePdf_whenAiReturnsFormalLetter() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper);

        StringBuilder sb = new StringBuilder();
        sb.append("Dear Ajuntament of El Prat,\n\n");
        sb.append("I am writing to express my concern about noise pollution near my residence. This has been ongoing and affects quality of life. ".repeat(30));
        sb.append("\n\nSincerely,\nA concerned resident");
        String aiMessage = "{\"format\": \"pdf\"}\n\n" + sb;

        // The fake will return a valid PDF header for [HEADER_LONG]
        OpenRouterResponseDto out = svc.redactComplaint("Some long complaint text [HEADER_LONG]", OutputFormat.AUTO, null);
        assertTrue(out.isSuccess(), "Service should report success");
        assertNotNull(out.getPdfData(), "PDF data should be produced for AUTO when AI message is a formal letter");
        String head = new String(out.getPdfData(), 0, Math.min(4, out.getPdfData().length), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF"), "PDF magic bytes expected at start of file");
    }
}
