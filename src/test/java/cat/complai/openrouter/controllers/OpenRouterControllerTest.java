package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.controllers.dto.AskRequest;
import cat.complai.openrouter.controllers.dto.RedactRequest;
import cat.complai.openrouter.dto.OutputFormat;
import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OpenRouterControllerTest {

    static class FakeServiceSuccess implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId) {
            return new OpenRouterResponseDto(true, "OK from AI", null, 200, OpenRouterErrorCode.NONE);
        }
        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
            return new OpenRouterResponseDto(true, "Redacted letter", null, 200, OpenRouterErrorCode.NONE);
        }
    }

    static class FakeServiceRefuse implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId) {
            return new OpenRouterResponseDto(false, null, "Request is not about El Prat de Llobregat.", 200, OpenRouterErrorCode.REFUSAL);
        }
        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
            return new OpenRouterResponseDto(false, null, "Request is not about El Prat de Llobregat.", 200, OpenRouterErrorCode.REFUSAL);
        }
    }

    static class FakeServiceUpstream implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId) {
            return new OpenRouterResponseDto(false, null, "Missing OPENROUTER_API_KEY", 500, OpenRouterErrorCode.UPSTREAM);
        }
        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
            return new OpenRouterResponseDto(false, null, "OpenRouter non-2xx response: 500", 500, OpenRouterErrorCode.UPSTREAM);
        }
    }

    // Service that rejects anonymous requests (mirrors service-layer behaviour)
    static class FakeServiceRejectAnonymous implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId) {
            return new OpenRouterResponseDto(true, "OK", null, 200, OpenRouterErrorCode.NONE);
        }
        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
            return new OpenRouterResponseDto(false, null,
                    "Anonymous complaints cannot be drafted. The Ajuntament requires full name and ID/DNI/NIF on all formal complaints.",
                    null, OpenRouterErrorCode.VALIDATION);
        }
    }

    // Service that returns a question asking for missing identity fields
    static class FakeServiceRequestsIdentity implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId) {
            return new OpenRouterResponseDto(true, "OK", null, 200, OpenRouterErrorCode.NONE);
        }
        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
            return new OpenRouterResponseDto(true,
                    "To draft your complaint I need your first name, surname, and ID/DNI/NIF.",
                    null, 200, OpenRouterErrorCode.NONE);
        }
    }

    @Test
    void ask_success_returns200() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess());
        AskRequest req = new AskRequest("Is there a recycling center?");
        HttpResponse<OpenRouterPublicDto> resp = c.ask(req);
        assertEquals(200, resp.getStatus().getCode());
        assertTrue(resp.getBody().isPresent());
        assertTrue(resp.getBody().get().isSuccess());
        assertEquals("OK from AI", resp.getBody().get().getMessage());
    }

    @Test
    void ask_refuse_returns422() {
        OpenRouterController c = new OpenRouterController(new FakeServiceRefuse());
        AskRequest req = new AskRequest("What's the capital of France?");
        HttpResponse<OpenRouterPublicDto> resp = c.ask(req);
        assertEquals(422, resp.getStatus().getCode());
        assertTrue(resp.getBody().isPresent());
        assertFalse(resp.getBody().get().isSuccess());
        assertEquals("Request is not about El Prat de Llobregat.", resp.getBody().get().getError());
    }

    @Test
    void ask_upstream_returns502() {
        OpenRouterController c = new OpenRouterController(new FakeServiceUpstream());
        AskRequest req = new AskRequest("Is there a recycling center?");
        HttpResponse<OpenRouterPublicDto> resp = c.ask(req);
        assertEquals(502, resp.getStatus().getCode());
        assertTrue(resp.getBody().isPresent());
        assertFalse(resp.getBody().get().isSuccess());
        assertEquals("Missing OPENROUTER_API_KEY", resp.getBody().get().getError());
    }

    @Test
    void redact_success_returns200() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess());
        RedactRequest req = new RedactRequest("Noise from the airport");
        HttpResponse<?> raw = c.redact(req);
        assertEquals(200, raw.getStatus().getCode());
        assertTrue(raw.getBody().isPresent());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertTrue(body.isSuccess());
        assertEquals("Redacted letter", body.getMessage());
    }

    @Test
    void redact_refuse_returns422() {
        OpenRouterController c = new OpenRouterController(new FakeServiceRefuse());
        RedactRequest req = new RedactRequest("How to cook paella?");
        HttpResponse<?> raw = c.redact(req);
        assertEquals(422, raw.getStatus().getCode());
        assertTrue(raw.getBody().isPresent());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertFalse(body.isSuccess());
        assertEquals("Request is not about El Prat de Llobregat.", body.getError());
    }

    @Test
    void redact_upstream_returns502() {
        OpenRouterController c = new OpenRouterController(new FakeServiceUpstream());
        RedactRequest req = new RedactRequest("Noise from the airport");
        HttpResponse<?> raw = c.redact(req);
        assertEquals(502, raw.getStatus().getCode());
        assertTrue(raw.getBody().isPresent());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertFalse(body.isSuccess());
        assertEquals("OpenRouter non-2xx response: 500", body.getError());
    }

    @Test
    void redact_unsupportedFormat_returns400WithClearMessage() {
        // null format means the client sent an unrecognised value (e.g. "xml") — OutputFormat.fromString
        // returns null for those. The controller must reject it before reaching the service.
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess());
        RedactRequest req = new RedactRequest("Noise from the airport", null);
        HttpResponse<?> raw = c.redact(req);
        assertEquals(400, raw.getStatus().getCode());
        assertTrue(raw.getBody().isPresent());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertFalse(body.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), body.getErrorCode());
        assertNotNull(body.getError());
        assertTrue(body.getError().contains("PDF"), "Error message must mention PDF as the only supported document format");
    }

    @Test
    void redact_anonymousRequest_returns400() {
        // Anonymous complaints must be rejected at the service layer and surfaced as 400 VALIDATION.
        OpenRouterController c = new OpenRouterController(new FakeServiceRejectAnonymous());
        RedactRequest req = new RedactRequest("Noise from the airport. I want to be anonymous.");
        HttpResponse<?> raw = c.redact(req);
        assertEquals(400, raw.getStatus().getCode());
        assertTrue(raw.getBody().isPresent());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertFalse(body.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), body.getErrorCode());
        assertTrue(body.getError().contains("Anonymous"), "Error must mention anonymous complaints");
    }

    @Test
    void redact_missingIdentity_returns200WithQuestion() {
        // When identity is absent the service asks for it via the AI response; the controller
        // must return 200 so the client can show the question to the user.
        OpenRouterController c = new OpenRouterController(new FakeServiceRequestsIdentity());
        RedactRequest req = new RedactRequest("Noise from the airport");
        HttpResponse<?> raw = c.redact(req);
        assertEquals(200, raw.getStatus().getCode());
        assertTrue(raw.getBody().isPresent());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertTrue(body.isSuccess());
        assertTrue(body.getMessage().contains("first name"), "Response must ask for the missing identity fields");
    }
}
