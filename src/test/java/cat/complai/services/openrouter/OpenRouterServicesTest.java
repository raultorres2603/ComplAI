package cat.complai.services.openrouter;

import cat.complai.utilities.http.HttpWrapper;
import cat.complai.dto.http.HttpDto;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.Source;
import cat.complai.helpers.openrouter.ProcedureRagHelperRegistry;
import cat.complai.helpers.openrouter.EventRagHelperRegistry;
import cat.complai.helpers.openrouter.ProcedureRagHelper;
import cat.complai.services.openrouter.ai.AiResponseProcessingService;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
import cat.complai.services.openrouter.procedure.ProcedureContextService;
import cat.complai.services.openrouter.validation.InputValidationService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OutputFormat;
import cat.complai.services.openrouter.cache.ResponseCacheService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenRouterServices. Uses a small fake HttpWrapper to avoid
 * network calls.
 */
public class OpenRouterServicesTest {

    // Helper method to create OpenRouterServices with new dependencies
    private OpenRouterServices createOpenRouterService(ScenarioFakeWrapper wrapper) {
        InputValidationService validationService = new InputValidationService(5000);
        ConversationManagementService conversationService = new ConversationManagementService(5);
        ResponseCacheService cacheService = new ResponseCacheService(true, 10, 500);
        AiResponseProcessingService aiResponseService = new AiResponseProcessingService(wrapper, cacheService, 30);
        ProcedureContextService procedureContextService = new ProcedureContextService(wrapper.ragRegistry,
                new EventRagHelperRegistry(), new RedactPromptBuilder());
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return new OpenRouterServices(validationService, conversationService, aiResponseService,
                procedureContextService, new RedactPromptBuilder(), wrapper, objectMapper);
    }

    // Flexible fake wrapper that simulates all test scenarios based on the last
    // user message content.
    // The service always calls postToOpenRouterAsync(List) with the assembled
    // message history,
    // so that is the overload we must override to intercept calls correctly.
    static class ScenarioFakeWrapper extends HttpWrapper {
        // Add a field to allow test to override the next response
        private String nextResponse = null;
        private List<ProcedureRagHelper.Procedure> fakeProcedures = List.of();
        private final ProcedureRagHelperRegistry ragRegistry = new ProcedureRagHelperRegistry() {
            @Override
            public ProcedureRagHelper getForCity(String cityId) {
                try {
                    return new ProcedureRagHelper(cityId) {
                        @Override
                        public List<ProcedureRagHelper.Procedure> search(String query) {
                            return fakeProcedures;
                        }
                    };
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        public List<Map<String, Object>> lastMessages;

        public void overrideNextResponse(String response) {
            this.nextResponse = response;
        }

        public void overrideFakeProcedures(List<ProcedureRagHelper.Procedure> fakeProcedures) {
            this.fakeProcedures = fakeProcedures;
        }

        @Override
        public CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages) {
            this.lastMessages = messages;
            if (nextResponse != null) {
                String resp = nextResponse;
                nextResponse = null;
                return CompletableFuture.completedFuture(new HttpDto(resp, 200, "POST", null));
            }

            // Extract the content of the last user message to determine which scenario to
            // run.
            String userPrompt = messages == null ? null
                    : messages.stream()
                            .filter(m -> "user".equals(m.get("role")))
                            .reduce((first, second) -> second)
                            .map(m -> (String) m.get("content"))
                            .orElse(null);

            if (userPrompt != null && userPrompt.contains("[REFUSE]")) {
                return CompletableFuture.completedFuture(new HttpDto(
                        "I'm sorry, I can't help with that request because it's not about El Prat de Llobregat.", 200,
                        "POST", null));
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
            return CompletableFuture.completedFuture(
                    new HttpDto("Dear Ajuntament,\n\nI am writing to complain about...\n\nSincerely,\nResident", 200,
                            "POST", null));
        }
    }

    @Test
    void ask_happyPath_returnsAiMessage() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        String input = "Is there a recycling center in El Prat de Llobregat?";
        OpenRouterResponseDto out = svc.ask(input, null, "testcity");
        assertTrue(out.isSuccess());
        assertEquals("<p>Hello from El Prat AI</p>", out.getMessage());
        assertNull(out.getError());
    }

    @Test
    void ask_withProcedureMatches_includesSources() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);
        List<ProcedureRagHelper.Procedure> fakeProcs = List.of(
                new ProcedureRagHelper.Procedure("p1", "Recycling", "Desc", "Req", "Steps",
                        "https://example.com/recycling"),
                new ProcedureRagHelper.Procedure("p2", "Waste", "Desc", "Req", "Steps", "https://example.com/waste"));
        wrapper.overrideFakeProcedures(fakeProcs);
        wrapper.overrideNextResponse("Answer about recycling");

        OpenRouterResponseDto out = svc.ask("recycling", null, "testcity");
        assertTrue(out.isSuccess());
        assertEquals("<p>Answer about recycling</p>", out.getMessage());
        List<Source> sources = out.getSources();
        assertEquals(2, sources.size());

        // Check sources by URL and title
        boolean foundRecycling = false;
        boolean foundWaste = false;
        for (Source source : sources) {
            if ("https://example.com/recycling".equals(source.getUrl()) && "Recycling".equals(source.getTitle())) {
                foundRecycling = true;
            }
            if ("https://example.com/waste".equals(source.getUrl()) && "Waste".equals(source.getTitle())) {
                foundWaste = true;
            }
        }
        assertTrue(foundRecycling, "Recycling source should be present with correct URL and title");
        assertTrue(foundWaste, "Waste source should be present with correct URL and title");
    }

    @Test
    void ask_withoutProcedureMatches_sourcesEmpty() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);
        wrapper.overrideFakeProcedures(List.of());
        wrapper.overrideNextResponse("Answer without sources");

        OpenRouterResponseDto out = svc.ask("anything", null, "testcity");
        assertTrue(out.isSuccess());
        assertEquals("<p>Answer without sources</p>", out.getMessage());
        assertNotNull(out.getSources());
        assertTrue(out.getSources().isEmpty());
    }

    @Test
    void ask_greeting_noSourcesIncluded() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);
        wrapper.overrideFakeProcedures(List.of()); // No procedure matches for greeting
        wrapper.overrideNextResponse("Hello! How can I help you today?");

        OpenRouterResponseDto out = svc.ask("Hello", null, "testcity");
        assertTrue(out.isSuccess());
        assertEquals("<p>Hello! How can I help you today?</p>", out.getMessage());
        assertNotNull(out.getSources());
        assertTrue(out.getSources().isEmpty(), "Greetings should not include sources");
    }

    @Test
    void ask_wrapperError_surfacesError() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        String input = "How to contact the Ajuntament of El Prat? [UPSTREAM]";
        OpenRouterResponseDto out = svc.ask(input, null, "testcity");
        assertFalse(out.isSuccess());
        assertEquals("Upstream error", out.getError());
    }

    @Test
    void ask_aiRefuses_whenNotAboutElPrat() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        String input = "What is the capital of France? [REFUSE]";
        OpenRouterResponseDto out = svc.ask(input, null, "elprat");
        assertFalse(out.isSuccess());
        assertEquals("Request is not about El Prat de Llobregat.", out.getError());
    }

    @Test
    void redact_emptyComplaint_rejects() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        InputValidationService validationService = new InputValidationService(5000);
        ConversationManagementService conversationService = new ConversationManagementService(5);
        ResponseCacheService cacheService = new ResponseCacheService(true, 10, 500);
        AiResponseProcessingService aiResponseService = new AiResponseProcessingService(wrapper, cacheService, 30);
        ProcedureContextService procedureContextService = new ProcedureContextService(wrapper.ragRegistry,
                new EventRagHelperRegistry(), new RedactPromptBuilder());
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        OpenRouterServices svc = new OpenRouterServices(validationService, conversationService, aiResponseService,
                procedureContextService, new RedactPromptBuilder(), wrapper, objectMapper);

        OpenRouterResponseDto out = svc.redactComplaint("   ", OutputFormat.JSON, null, null, "testcity");
        assertFalse(out.isSuccess());
        assertEquals("Complaint must not be empty.", out.getError());
    }

    @Test
    void redact_anonymousRequest_english_rejectsWithValidation() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        OpenRouterResponseDto out = svc.redactComplaint(
                "Noise from the airport. I want to remain anonymous.", OutputFormat.JSON, null, null, "testcity");
        assertFalse(out.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION, out.getErrorCode());
        assertTrue(out.getError().contains("Anonymous"), "Error must mention anonymous complaints");
    }

    @Test
    void redact_anonymousRequest_spanish_rejectsWithValidation() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        OpenRouterResponseDto out = svc.redactComplaint(
                "Ruido del aeropuerto. Quiero que sea anónimo.", OutputFormat.JSON, null, null, "testcity");
        assertFalse(out.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION, out.getErrorCode());
    }

    @Test
    void redact_anonymousRequest_catalan_rejectsWithValidation() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        OpenRouterResponseDto out = svc.redactComplaint(
                "Soroll de l'aeroport. Vull ser anònim.", OutputFormat.JSON, null, null, "testcity");
        assertFalse(out.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION, out.getErrorCode());
    }

    @Test
    void redact_missingIdentity_aiAsksForIt_returns200WithQuestion() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        OpenRouterResponseDto out = svc.redactComplaint(
                "Noise from the airport [ASKS_IDENTITY]", OutputFormat.JSON, null, null, "testcity");
        assertTrue(out.isSuccess(), "Service must return success so the client can show the AI's question");
        assertNotNull(out.getMessage());
        assertTrue(out.getMessage().contains("first name"), "AI response must ask for the missing identity fields");
        assertNull(out.getPdfData(), "No PDF should be produced when identity is missing");
    }

    @Test
    void redact_completeIdentity_returnsLetterAsText() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint(
                "Noise from the airport [IDENTITY_LETTER]", OutputFormat.PDF, null, identity, "testcity");
        assertTrue(out.isSuccess());
        assertNull(out.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(out.getMessage(), "Letter body must be returned as text");
        assertTrue(out.getMessage().contains("Joan Garcia"), "Letter must contain the complainant's name");
    }

    @Test
    void redact_partialIdentity_aiAsksForMissingFields() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        ComplainantIdentity partial = new ComplainantIdentity("Joan", null, null);
        OpenRouterResponseDto out = svc.redactComplaint(
                "Noise from the airport [ASKS_IDENTITY]", OutputFormat.JSON, null, partial, "testcity");
        assertTrue(out.isSuccess());
        assertNotNull(out.getMessage());
        assertNull(out.getPdfData());
    }

    @Test
    void redact_pdfRequested_returnsLetterAsText() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [HEADER]", OutputFormat.PDF, null,
                identity, "testcity");
        assertTrue(out.isSuccess());
        assertNull(out.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(out.getMessage(), "Letter body must be returned as text");
    }

    @Test
    void redact_missingJsonHeader_withAutoFormat_fallsBackToJsonSuccess() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [NOHEADER]", OutputFormat.AUTO, null,
                identity, "testcity");

        assertTrue(out.isSuccess(), "Missing header with AUTO must not fail the request");
        assertNotNull(out.getMessage());
    }

    @Test
    void redact_missingJsonHeader_withJsonFormat_fallsBackToJsonSuccess() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [NOHEADER]", OutputFormat.JSON, null,
                identity, "testcity");

        assertTrue(out.isSuccess(), "Missing header with JSON must not fail the request");
        assertNotNull(out.getMessage());
    }

    @Test
    void redact_missingJsonHeader_withPdfFormat_returnsRawTextGracefully() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Some complaint text [NOHEADER]", OutputFormat.PDF, null,
                identity, "testcity");

        assertTrue(out.isSuccess(), "Missing header with explicit PDF format must still succeed");
        assertNull(out.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(out.getMessage());
    }

    @Test
    void redact_auto_returnsLetterAsText_whenAiReturnsFormalLetter() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Some long complaint text [HEADER_LONG]", OutputFormat.AUTO,
                null, identity, "testcity");
        assertTrue(out.isSuccess(), "Service should report success");
        assertNull(out.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(out.getMessage(), "Letter body must be returned as text");
    }

    @Test
    void ask_tooLong_rejects() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);
        OpenRouterResponseDto out = svc.ask("a".repeat(5001), null, "testcity");
        assertFalse(out.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION, out.getErrorCode());
        assertTrue(out.getError().contains("maximum allowed length"));
    }

    @Test
    void redactComplaint_tooLong_rejects() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);
        OpenRouterResponseDto out = svc.redactComplaint("b".repeat(5001), OutputFormat.JSON, null, null, "testcity");
        assertFalse(out.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION, out.getErrorCode());
        assertTrue(out.getError().contains("maximum allowed length"));
    }

    @Test
    void redact_pdfRequested_unicodeCatalanCharacters() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);
        String catalanText = "Això és una prova amb ç, à, ü, ·l, ñ, and œ.";
        String aiResponse = "{\"format\": \"pdf\"}\n\n" + catalanText;
        wrapper.overrideNextResponse(aiResponse);
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto out = svc.redactComplaint("Prova unicode català [HEADER]", OutputFormat.PDF, null,
                identity, "testcity");
        assertTrue(out.isSuccess());
        assertNull(out.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(out.getMessage(), "Letter body with Unicode characters must be returned as text");
        assertTrue(out.getMessage().contains("ç"), "Catalan characters must be preserved in the text response");
    }

    @Test
    void redact_verifiesPromptInstructions() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        // pre-load a fake response so the service call completes successfully
        wrapper.overrideNextResponse("{\"format\": \"pdf\"}\n\nLetter content...");
        OpenRouterServices svc = createOpenRouterService(wrapper);

        ComplainantIdentity identity = new ComplainantIdentity("Raul", "Torres", "12345678A");
        svc.redactComplaint("Fix the street", OutputFormat.PDF, null, identity, "testcity");

        assertNotNull(wrapper.lastMessages, "Messages must be captured");
        String prompt = (String) wrapper.lastMessages.getLast().get("content");

        assertTrue(prompt.contains("Date:"), "Prompt must contain date instruction");
        assertTrue(prompt.contains("PLAIN TEXT output"), "Prompt must forbid markdown");
        assertTrue(prompt.contains("Raul Torres"), "Prompt must contain the name");
        assertTrue(prompt.contains("12345678A"), "Prompt must contain the ID");
    }

    @Test
    void redact_incompleteIdentity_aiResponseReturnedAsText() {
        ScenarioFakeWrapper wrapper = new ScenarioFakeWrapper();
        OpenRouterServices svc = createOpenRouterService(wrapper);

        OpenRouterResponseDto out = svc.redactComplaint(
                "My name is Raul Torres, ID 49872354C. [SMART_EXTRACT]", OutputFormat.PDF, null, null, "testcity");

        assertTrue(out.isSuccess());
        assertNull(out.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(out.getMessage(), "AI response must be returned as text");
        assertEquals(OpenRouterErrorCode.NONE, out.getErrorCode());
    }
}
