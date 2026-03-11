package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.RedactAcceptedDto;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.controllers.dto.AskRequest;
import cat.complai.openrouter.controllers.dto.RedactRequest;
import cat.complai.openrouter.dto.OutputFormat;
import cat.complai.s3.S3PdfUploader;
import cat.complai.sqs.SqsComplaintPublisher;
import cat.complai.sqs.dto.RedactSqsMessage;
import io.micronaut.http.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class OpenRouterControllerTest {

    // -------------------------------------------------------------------------
    // Fake services
    // -------------------------------------------------------------------------

    static class FakeServiceSuccess implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId) {
            return new OpenRouterResponseDto(true, "OK from AI", null, 200, OpenRouterErrorCode.NONE);
        }
        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
            return new OpenRouterResponseDto(true, "Redacted letter", null, 200, OpenRouterErrorCode.NONE);
        }
        public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
            return Optional.empty();
        }
    }

    static class FakeServiceRefuse implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId) {
            return new OpenRouterResponseDto(false, null, "Request is not about El Prat de Llobregat.", 200, OpenRouterErrorCode.REFUSAL);
        }
        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
            return new OpenRouterResponseDto(false, null, "Request is not about El Prat de Llobregat.", 200, OpenRouterErrorCode.REFUSAL);
        }
        public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
            return Optional.empty();
        }
    }

    static class FakeServiceUpstream implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId) {
            return new OpenRouterResponseDto(false, null, "Missing OPENROUTER_API_KEY", 500, OpenRouterErrorCode.UPSTREAM);
        }
        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
            return new OpenRouterResponseDto(false, null, "OpenRouter non-2xx response: 500", 500, OpenRouterErrorCode.UPSTREAM);
        }
        public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
            return Optional.empty();
        }
    }

    static class FakeServiceRejectAnonymous implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId) {
            return new OpenRouterResponseDto(true, "OK", null, 200, OpenRouterErrorCode.NONE);
        }
        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
            return new OpenRouterResponseDto(false, null,
                    "Anonymous complaints cannot be drafted. The Ajuntament requires full name and ID/DNI/NIF on all formal complaints.",
                    null, OpenRouterErrorCode.VALIDATION);
        }
        public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
            if (complaint != null && complaint.toLowerCase().contains("anonymous")) {
                return Optional.of(new OpenRouterResponseDto(false, null,
                        "Anonymous complaints cannot be drafted. The Ajuntament requires full name and ID/DNI/NIF on all formal complaints.",
                        null, OpenRouterErrorCode.VALIDATION));
            }
            return Optional.empty();
        }
    }

    static class FakeServiceRequestsIdentity implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId) {
            return new OpenRouterResponseDto(true, "OK", null, 200, OpenRouterErrorCode.NONE);
        }
        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId, ComplainantIdentity identity) {
            return new OpenRouterResponseDto(true,
                    "To draft your complaint I need your first name, surname, and ID/DNI/NIF.",
                    null, 200, OpenRouterErrorCode.NONE);
        }
        public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Fake infrastructure stubs
    // -------------------------------------------------------------------------

    /** Stub that asserts the SQS publisher is never called — used by sync-path tests. */
    static final SqsComplaintPublisher ASSERT_NOT_CALLED_PUBLISHER = new SqsComplaintPublisher() {
        @Override
        public void publish(RedactSqsMessage message) {
            throw new AssertionError("SqsComplaintPublisher.publish() must not be called in this test");
        }
    };

    /** Stub that asserts the S3 uploader is never called — used by sync-path tests. */
    static final S3PdfUploader ASSERT_NOT_CALLED_UPLOADER = new S3PdfUploader() {
        @Override
        public String generatePresignedGetUrl(String key) {
            throw new AssertionError("S3PdfUploader.generatePresignedGetUrl() must not be called in this test");
        }
    };

    /** No-op publisher used by async-path tests. */
    static final SqsComplaintPublisher NOOP_PUBLISHER = new SqsComplaintPublisher() {
        @Override
        public void publish(RedactSqsMessage message) { /* success, do nothing */ }
    };

    /** Returns a fixed URL so async-path tests can assert on it. */
    static final S3PdfUploader FIXED_URL_UPLOADER = new S3PdfUploader() {
        @Override
        public String generatePresignedGetUrl(String key) {
            return "https://bucket.s3.eu-west-1.amazonaws.com/" + key;
        }
    };

    /** Publisher that always throws — simulates SQS unavailability. */
    static final SqsComplaintPublisher FAILING_PUBLISHER = new SqsComplaintPublisher() {
        @Override
        public void publish(RedactSqsMessage message) {
            throw new RuntimeException("SQS service unavailable");
        }
    };

    // -------------------------------------------------------------------------
    // ask() tests
    // -------------------------------------------------------------------------

    @Test
    void ask_success_returns200() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER);
        HttpResponse<OpenRouterPublicDto> resp = c.ask(new AskRequest("Is there a recycling center?"));
        assertEquals(200, resp.getStatus().getCode());
        assertTrue(resp.getBody().get().isSuccess());
        assertEquals("OK from AI", resp.getBody().get().getMessage());
    }

    @Test
    void ask_refuse_returns422() {
        OpenRouterController c = new OpenRouterController(new FakeServiceRefuse(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER);
        HttpResponse<OpenRouterPublicDto> resp = c.ask(new AskRequest("What's the capital of France?"));
        assertEquals(422, resp.getStatus().getCode());
        assertFalse(resp.getBody().get().isSuccess());
    }

    @Test
    void ask_upstream_returns502() {
        OpenRouterController c = new OpenRouterController(new FakeServiceUpstream(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER);
        HttpResponse<OpenRouterPublicDto> resp = c.ask(new AskRequest("Is there a recycling center?"));
        assertEquals(502, resp.getStatus().getCode());
    }

    // -------------------------------------------------------------------------
    // redact() — sync path
    // -------------------------------------------------------------------------

    @Test
    void redact_success_returns200() {
        // No identity → sync path → SQS/S3 must not be called.
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER);
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport"));
        assertEquals(200, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertTrue(body.isSuccess());
        assertEquals("Redacted letter", body.getMessage());
    }

    @Test
    void redact_refuse_returns422() {
        OpenRouterController c = new OpenRouterController(new FakeServiceRefuse(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER);
        HttpResponse<?> raw = c.redact(new RedactRequest("How to cook paella?"));
        assertEquals(422, raw.getStatus().getCode());
    }

    @Test
    void redact_upstream_returns502() {
        OpenRouterController c = new OpenRouterController(new FakeServiceUpstream(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER);
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport"));
        assertEquals(502, raw.getStatus().getCode());
    }

    @Test
    void redact_unsupportedFormat_returns400WithClearMessage() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER);
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport", null));
        assertEquals(400, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertFalse(body.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), body.getErrorCode());
        assertTrue(body.getError().contains("PDF"));
    }

    @Test
    void redact_anonymousRequest_returns400() {
        OpenRouterController c = new OpenRouterController(new FakeServiceRejectAnonymous(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER);
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport. I want to be anonymous."));
        assertEquals(400, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertFalse(body.isSuccess());
        assertTrue(body.getError().contains("Anonymous"));
    }

    @Test
    void redact_missingIdentity_returns200WithQuestion() {
        OpenRouterController c = new OpenRouterController(new FakeServiceRequestsIdentity(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER);
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport"));
        assertEquals(200, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertTrue(body.isSuccess());
        assertTrue(body.getMessage().contains("first name"));
    }

    @Test
    void redact_completeIdentity_jsonFormat_usesSyncPath() {
        // When format is JSON, even complete identity must stay synchronous.
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER);
        RedactRequest req = RedactRequest.fromJson("Noise from the airport", "json", null, "Joan", "Garcia", "12345678A");
        HttpResponse<?> raw = c.redact(req);
        assertEquals(200, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertTrue(body.isSuccess());
    }

    // -------------------------------------------------------------------------
    // redact() — async path (complete identity + PDF/AUTO format)
    // -------------------------------------------------------------------------

    @Test
    void redact_completeIdentityAndPdfFormat_returns202WithPdfUrl() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), NOOP_PUBLISHER, FIXED_URL_UPLOADER);
        RedactRequest req = RedactRequest.fromJson("Noise from the airport", "pdf", null, "Joan", "Garcia", "12345678A");
        HttpResponse<?> raw = c.redact(req);
        assertEquals(202, raw.getStatus().getCode());
        RedactAcceptedDto body = (RedactAcceptedDto) raw.getBody().get();
        assertTrue(body.success());
        assertNotNull(body.pdfUrl());
        assertTrue(body.pdfUrl().contains("complaint.pdf"), "pdfUrl must point to a PDF key");
        assertEquals(OpenRouterErrorCode.NONE.getCode(), body.errorCode());
    }

    @Test
    void redact_completeIdentityAndAutoFormat_returns202WithPdfUrl() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), NOOP_PUBLISHER, FIXED_URL_UPLOADER);
        RedactRequest req = RedactRequest.fromJson("Noise from the airport", "auto", null, "Joan", "Garcia", "12345678A");
        HttpResponse<?> raw = c.redact(req);
        assertEquals(202, raw.getStatus().getCode());
        RedactAcceptedDto body = (RedactAcceptedDto) raw.getBody().get();
        assertTrue(body.success());
        assertNotNull(body.pdfUrl());
    }

    @Test
    void redact_asyncPath_sqsPublishFailure_returns500() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), FAILING_PUBLISHER, FIXED_URL_UPLOADER);
        RedactRequest req = RedactRequest.fromJson("Noise from the airport", "pdf", null, "Joan", "Garcia", "12345678A");
        HttpResponse<?> raw = c.redact(req);
        assertEquals(500, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertFalse(body.isSuccess());
        assertEquals(OpenRouterErrorCode.INTERNAL.getCode(), body.getErrorCode());
    }

    @Test
    void redact_asyncPath_validationFailure_returns400() {
        // Anonymity check is run before SQS publish; result must be 400, not 202.
        OpenRouterController c = new OpenRouterController(new FakeServiceRejectAnonymous(), ASSERT_NOT_CALLED_PUBLISHER, FIXED_URL_UPLOADER);
        // Complete identity BUT anonymous request — validation must short-circuit.
        RedactRequest req = RedactRequest.fromJson("I want to be anonymous.", "pdf", null, "Joan", "Garcia", "12345678A");
        HttpResponse<?> raw = c.redact(req);
        assertEquals(400, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertFalse(body.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), body.getErrorCode());
    }
}
