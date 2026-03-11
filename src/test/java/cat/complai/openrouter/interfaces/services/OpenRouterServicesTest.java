package cat.complai.openrouter.interfaces.services;

import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.services.OpenRouterServices;
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
        // Add a field to allow test to override the next response
        private String nextResponse = null;
        public List<Map<String, Object>> lastMessages;

        public void overrideNextResponse(String response) {
            this.nextResponse = response;
        }
        @Override
        public CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages) {
            this.lastMessages = messages;
            if (nextResponse != null) {
                String resp = nextResponse;
                nextResponse = null;
                return CompletableFuture.completedFuture(new HttpDto(resp, 200, "POST", null));
            }

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
                String sb = "{\"format\": \"pdf\"}\n\nDear Ajuntament,\n\n" +
                        "This is a long complaint sentence to generate many pages. ".repeat(800) +
                        "\n\nSincerely,\nResident";
                return CompletableFuture.completedFuture(new HttpDto(sb, 200, "POST", null));
            }
            if (userPrompt != null && userPrompt.contains("[HEADER_INVALID]")) {
                String body = "{\"format\": \"xml\"}\n\nThis body should be rejected due to invalid format.";
                return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
            }
            if (userPrompt != null && userPrompt.contains("[ASKS_IDENTITY]")) {
                // Simulate the AI asking for missing identity fields
                return CompletableFuture.completedFuture(new HttpDto(
                        "To draft your complaint I need your first name, surname, and ID/DNI/NIF. Could you please provide them?",
                        200, "POST", null));
            }
            if (userPrompt != null && userPrompt.contains("[IDENTITY_LETTER]")) {
                // Simulate the AI drafting a letter with identity embedded
                String body = "{\"format\": \"pdf\"}\n\nEl Prat de Llobregat, 10 de març de 2026\n\nSr. Alcalde,\n\nJo, Joan Garcia, amb DNI 12345678A, vull presentar una queixa...\n\nAtentament,\nJoan Garcia\nDNI: 12345678A";
                return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
            }
            if (userPrompt != null && userPrompt.contains("[SMART_EXTRACT]")) {
                // Simulate AI successfully extracting identity from text and promoting to PDF
                String body = "{\"format\": \"pdf\"}\n\nEl Prat de Llobregat, 10 de març de 2026\n\nSr. Alcalde,\n\nJo, Raul Torres, amb DNI 49872354C...\n\nAtentament,\nRaul Torres";
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
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        String input = "Is there a recycling center in El Prat de Llobregat?";
        OpenRouterResponseDto out = svc.ask(input, null);
        assertTrue(out.isSuccess());
        assertEquals("Hello from El Prat AI", out.getMessage());
        assertNull(out.getError());
    }

    @Test
    void ask_wrapperError_surfacesError() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        String input = "How to contact the Ajuntament of El Prat? [UPSTREAM]";
        OpenRouterResponseDto out = svc.ask(input, null);
        assertFalse(out.isSuccess());
        assertEquals("Upstream error", out.getError());
    }

    @Test
    void ask_aiRefuses_whenNotAboutElPrat() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        String input = "What is the capital of France? [REFUSE]";
        OpenRouterResponseDto out = svc.ask(input, null);
        assertFalse(out.isSuccess());
        assertEquals("Request is not about El Prat de Llobregat.", out.getError());
    }

    @Test
    void redact_emptyComplaint_rejects() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        OpenRouterResponseDto out = svc.redactComplaint("   ", OutputFormat.JSON, null, null);
        assertFalse(out.isSuccess());
        assertEquals("Complaint must not be empty.", out.getError());
    }

    @Test
    void redact_anonymousRequest_english_rejectsWithValidation() {
        // The service must reject anonymous complaints immediately without calling the AI.
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        OpenRouterResponseDto out = svc.redactComplaint(
                "Noise from the airport. I want to remain anonymous.", OutputFormat.JSON, null, null);
        assertFalse(out.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION, out.getErrorCode());
        assertTrue(out.getError().contains("Anonymous"), "Error must mention anonymous complaints");
    }

    @Test
    void redact_anonymousRequest_spanish_rejectsWithValidation() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        OpenRouterResponseDto out = svc.redactComplaint(
                "Ruido del aeropuerto. Quiero que sea anónimo.", OutputFormat.JSON, null, null);
        assertFalse(out.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION, out.getErrorCode());
    }

    @Test
    void redact_anonymousRequest_catalan_rejectsWithValidation() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        OpenRouterResponseDto out = svc.redactComplaint(
                "Soroll de l'aeroport. Vull ser anònim.", OutputFormat.JSON, null, null);
        assertFalse(out.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION, out.getErrorCode());
    }

    @Test
    void redact_missingIdentity_aiAsksForIt_returns200WithQuestion() {
        // When identity is absent, the service must instruct the AI to ask the user for
        // the missing fields, and return the AI's question as a 200 success to the client.
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        OpenRouterResponseDto out = svc.redactComplaint(
                "Noise from the airport [ASKS_IDENTITY]", OutputFormat.JSON, null, null);
        assertTrue(out.isSuccess(), "Service must return success so the client can show the AI's question");
        assertNotNull(out.getMessage());
        assertTrue(out.getMessage().contains("first name"), "AI response must ask for the missing identity fields");
        assertNull(out.getPdfData(), "No PDF should be produced when identity is missing");
    }

    @Test
    void redact_completeIdentity_producesPdf() {
        // When full identity is provided, the service must produce a PDF letter with the
        // complainant's information embedded.
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint(
                "Noise from the airport [IDENTITY_LETTER]", OutputFormat.PDF, null, identity);
        assertTrue(out.isSuccess());
        assertNotNull(out.getPdfData());
        String head = new String(out.getPdfData(), 0, Math.min(4, out.getPdfData().length), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF"), "PDF magic bytes expected at start of file");
    }

    @Test
    void redact_partialIdentity_aiAsksForMissingFields() {
        // A partially-filled identity (e.g. name only, no surname or ID) is treated as
        // missing — the AI must be asked to collect the remaining fields.
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        ComplainantIdentity partial = new ComplainantIdentity("Joan", null, null);
        OpenRouterResponseDto out = svc.redactComplaint(
                "Noise from the airport [ASKS_IDENTITY]", OutputFormat.JSON, null, partial);
        assertTrue(out.isSuccess());
        assertNotNull(out.getMessage());
        assertNull(out.getPdfData());
    }

    @Test
    void redact_pdfRequested_returnsPdfMagicBytes() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [HEADER]", OutputFormat.PDF, null, identity);
        assertTrue(out.isSuccess());
        assertNotNull(out.getPdfData());
        String head = new String(out.getPdfData(), 0, Math.min(4, out.getPdfData().length), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF"), "PDF magic bytes expected at start of file");
    }

    @Test
    void redact_missingJsonHeader_withAutoFormat_fallsBackToJsonSuccess() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        String aiMessage = "Dear Ajuntament,\n\nI am writing to complain about...\n\nSincerely,\nResident";
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [NOHEADER]", OutputFormat.AUTO, null, identity);

        assertTrue(out.isSuccess(), "Missing header with AUTO must not fail the request");
        assertEquals(aiMessage, out.getMessage(), "Raw AI message must be returned as-is");
        assertNotNull(out.getMessage());
    }

    @Test
    void redact_missingJsonHeader_withJsonFormat_fallsBackToJsonSuccess() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        String aiMessage = "Dear Ajuntament,\n\nI am writing to complain about...\n\nSincerely,\nResident";
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [NOHEADER]", OutputFormat.JSON, null, identity);

        assertTrue(out.isSuccess(), "Missing header with JSON must not fail the request");
        assertEquals(aiMessage, out.getMessage());
    }

    @Test
    void redact_missingJsonHeader_withPdfFormat_producesPdfFromRawMessage() {
        // When the client explicitly asked for PDF but the AI omits the JSON header, the service
        // must still produce a PDF. The header is only a format hint — the letter content is in
        // the raw AI message and is usable for PDF generation without it.
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [NOHEADER]", OutputFormat.PDF, null, identity);

        assertTrue(out.isSuccess(), "Missing header with explicit PDF must still succeed");
        assertNotNull(out.getPdfData(), "PDF must be generated from the raw AI message");
        String head = new String(out.getPdfData(), 0, Math.min(4, out.getPdfData().length), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF"), "PDF magic bytes expected at start of file");
    }

    @Test
    void redact_auto_shouldProducePdf_whenAiReturnsFormalLetter() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Some long complaint text [HEADER_LONG]", OutputFormat.AUTO, null, identity);
        assertTrue(out.isSuccess(), "Service should report success");
        assertNotNull(out.getPdfData(), "PDF data should be produced for AUTO when AI message is a formal letter");
        String head = new String(out.getPdfData(), 0, Math.min(4, out.getPdfData().length), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF"), "PDF magic bytes expected at start of file");
    }

    @Test
    void ask_tooLong_rejects() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);
        OpenRouterResponseDto out = svc.ask("a".repeat(5001), null);
        assertFalse(out.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION, out.getErrorCode());
        assertTrue(out.getError().contains("maximum allowed length"));
    }

    @Test
    void redactComplaint_tooLong_rejects() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);
        OpenRouterResponseDto out = svc.redactComplaint("b".repeat(5001), OutputFormat.JSON, null, null);
        assertFalse(out.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION, out.getErrorCode());
        assertTrue(out.getError().contains("maximum allowed length"));
    }

    @Test
    void redact_pdfRequested_unicodeCatalanCharacters() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);
        // Simulate a PDF response with Catalan special characters
        String catalanText = "Això és una prova amb ç, à, ü, ·l, ñ, and œ.";
        String aiResponse = "{\"format\": \"pdf\"}\n\n" + catalanText;
        wrapper.overrideNextResponse(aiResponse);
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Prova unicode català [HEADER]", OutputFormat.PDF, null, identity);
        assertTrue(out.isSuccess());
        assertNotNull(out.getPdfData());
        String head = new String(out.getPdfData(), 0, Math.min(4, out.getPdfData().length), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("%PDF"), "PDF magic bytes expected at start of file");
    }

    @Test
    void redact_verifiesPromptInstructions() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        // pre-load a fake response so the service call completes successfully
        wrapper.overrideNextResponse("{\"format\": \"pdf\"}\n\nLetter content...");
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        ComplainantIdentity identity = new ComplainantIdentity("Raul", "Torres", "12345678A");
        svc.redactComplaint("Fix the street", OutputFormat.PDF, null, identity);

        assertNotNull(wrapper.lastMessages, "Messages must be captured");
        String prompt = (String) wrapper.lastMessages.getLast().get("content");

        assertTrue(prompt.contains("Use specifically this date:"), "Prompt must contain strict date instruction");
        assertTrue(prompt.contains("Do NOT use Markdown formatting"), "Prompt must forbid markdown");
        assertTrue(prompt.contains("Raul Torres"), "Prompt must contain the name");
        assertTrue(prompt.contains("12345678A"), "Prompt must contain the ID");
    }

    @Test
    void redact_incompleteIdentity_butTextHasIdentity_extractsAndSimulatesPdf() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = new OpenRouterServices(wrapper, 5000, 30);

        // Identity is null, but text contains the trigger which mocks a successful extraction response
        OpenRouterResponseDto out = svc.redactComplaint(
                "My name is Raul Torres, ID 49872354C. [SMART_EXTRACT]", OutputFormat.PDF, null, null);

        assertTrue(out.isSuccess());
        assertNotNull(out.getPdfData(), "Should produce PDF because AI extracted identity and returned PDF header");
        assertEquals(OpenRouterErrorCode.NONE, out.getErrorCode());
    }
}
