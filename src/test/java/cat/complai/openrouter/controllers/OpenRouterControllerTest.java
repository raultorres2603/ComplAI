package cat.complai.openrouter.controllers;

import cat.complai.auth.JwtAuthFilter;
import cat.complai.auth.IdentityTokenValidationException;
import cat.complai.auth.OidcIdentityTokenValidator;
import cat.complai.auth.VerifiedCitizenIdentity;
import cat.complai.openrouter.dto.AskStreamResult;
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
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.sse.Event;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class OpenRouterControllerTest {

    // -------------------------------------------------------------------------
    // Fake services
    // -------------------------------------------------------------------------

    static class FakeServiceSuccess implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId, String cityId) {
            return new OpenRouterResponseDto(true, "OK from AI", null, 200, OpenRouterErrorCode.NONE);
        }

        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId,
                ComplainantIdentity identity, String cityId) {
            return new OpenRouterResponseDto(true, "Redacted letter", null, 200, OpenRouterErrorCode.NONE);
        }

        public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
            return Optional.empty();
        }

        @Override
        public AskStreamResult streamAsk(String question, String conversationId, String cityId) {
            // Return properly formatted SSE events: chunk, sources, done
            return new AskStreamResult.Success(reactor.core.publisher.Flux.just(
                    "{\"type\":\"chunk\",\"content\":\"OK from AI\"}",
                    "{\"type\":\"sources\",\"sources\":[]}",
                    "{\"type\":\"done\",\"conversationId\":null}"));
        }
    }

    static class FakeServiceRefuse implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId, String cityId) {
            return new OpenRouterResponseDto(false, null, "Request is not about El Prat de Llobregat.", 200,
                    OpenRouterErrorCode.REFUSAL);
        }

        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId,
                ComplainantIdentity identity, String cityId) {
            return new OpenRouterResponseDto(false, null, "Request is not about El Prat de Llobregat.", 200,
                    OpenRouterErrorCode.REFUSAL);
        }

        public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
            return Optional.empty();
        }

        @Override
        public AskStreamResult streamAsk(String question, String conversationId, String cityId) {
            // Return error event in proper format
            return new AskStreamResult.Success(reactor.core.publisher.Flux.just(
                    "{\"type\":\"error\",\"error\":\"Request is not about El Prat de Llobregat.\",\"errorCode\":4}"));
        }
    }

    static class FakeServiceUpstream implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId, String cityId) {
            return new OpenRouterResponseDto(false, null, "Missing OPENROUTER_API_KEY", 500,
                    OpenRouterErrorCode.UPSTREAM);
        }

        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId,
                ComplainantIdentity identity, String cityId) {
            return new OpenRouterResponseDto(false, null, "OpenRouter non-2xx response: 500", 500,
                    OpenRouterErrorCode.UPSTREAM);
        }

        public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
            return Optional.empty();
        }

        @Override
        public AskStreamResult streamAsk(String question, String conversationId, String cityId) {
            return new AskStreamResult.Error(new OpenRouterResponseDto(
                    false,
                    null,
                    "AI service is temporarily unavailable. Please try again later.",
                    402,
                    OpenRouterErrorCode.UPSTREAM));
        }
    }

    static class FakeServiceRejectAnonymous implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId, String cityId) {
            return new OpenRouterResponseDto(true, "OK", null, 200, OpenRouterErrorCode.NONE);
        }

        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId,
                ComplainantIdentity identity, String cityId) {
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

        @Override
        public AskStreamResult streamAsk(String question, String conversationId, String cityId) {
            return new AskStreamResult.Success(reactor.core.publisher.Flux.empty());
        }
    }

    static class FakeServiceRequestsIdentity implements IOpenRouterService {
        public OpenRouterResponseDto ask(String question, String conversationId, String cityId) {
            return new OpenRouterResponseDto(true, "OK", null, 200, OpenRouterErrorCode.NONE);
        }

        public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format, String conversationId,
                ComplainantIdentity identity, String cityId) {
            return new OpenRouterResponseDto(true,
                    "To draft your complaint I need your first name, surname, and ID/DNI/NIF.",
                    null, 200, OpenRouterErrorCode.NONE);
        }

        public Optional<OpenRouterResponseDto> validateRedactInput(String complaint) {
            return Optional.empty();
        }

        @Override
        public AskStreamResult streamAsk(String question, String conversationId, String cityId) {
            return new AskStreamResult.Success(reactor.core.publisher.Flux.empty());
        }
    }

    // -------------------------------------------------------------------------
    // Fake infrastructure stubs
    // -------------------------------------------------------------------------

    /**
     * Stub that asserts the SQS publisher is never called — used by sync-path
     * tests.
     */
    static final SqsComplaintPublisher ASSERT_NOT_CALLED_PUBLISHER = new SqsComplaintPublisher() {
        @Override
        public void publish(RedactSqsMessage message) {
            throw new AssertionError("SqsComplaintPublisher.publish() must not be called in this test");
        }
    };

    /**
     * Stub that asserts the S3 uploader is never called — used by sync-path tests.
     */
    static final S3PdfUploader ASSERT_NOT_CALLED_UPLOADER = new S3PdfUploader() {
        @Override
        public String generatePresignedGetUrl(String key) {
            throw new AssertionError("S3PdfUploader.generatePresignedGetUrl() must not be called in this test");
        }
    };

    /** No-op publisher used by async-path tests. */
    static final SqsComplaintPublisher NOOP_PUBLISHER = new SqsComplaintPublisher() {
        @Override
        public void publish(RedactSqsMessage message) {
            /* success, do nothing */ }
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
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), ASSERT_NOT_CALLED_PUBLISHER,
                ASSERT_NOT_CALLED_UPLOADER, null);
        HttpResponse<?> raw = c.ask(new AskRequest("Is there a recycling center?"), requestWithCity("testcity"));
        @SuppressWarnings("unchecked")
        org.reactivestreams.Publisher<Event<String>> publisher = (org.reactivestreams.Publisher<Event<String>>) raw
                .getBody()
                .orElseThrow();
        List<Event<String>> events = reactor.core.publisher.Flux.from(publisher).collectList().block();
        assertFalse(events.isEmpty());
        assertEquals("{\"type\":\"chunk\",\"content\":\"OK from AI\"}", events.get(0).getData());
    }

    @Test
    void ask_refuse_returns422() {
        OpenRouterController c = new OpenRouterController(new FakeServiceRefuse(), ASSERT_NOT_CALLED_PUBLISHER,
                ASSERT_NOT_CALLED_UPLOADER, null);
        HttpResponse<?> raw = c.ask(new AskRequest("What's the capital of France?"), requestWithCity("testcity"));
        @SuppressWarnings("unchecked")
        org.reactivestreams.Publisher<Event<String>> publisher = (org.reactivestreams.Publisher<Event<String>>) raw
                .getBody()
                .orElseThrow();
        List<Event<String>> events = reactor.core.publisher.Flux.from(publisher).collectList().block();
        assertFalse(events.isEmpty());
        assertTrue(events.get(0).getData().contains("El Prat"));
    }

    @Test
    void ask_upstream_returns502() {
        OpenRouterController c = new OpenRouterController(new FakeServiceUpstream(), ASSERT_NOT_CALLED_PUBLISHER,
                ASSERT_NOT_CALLED_UPLOADER, null);
        HttpResponse<?> raw = c.ask(new AskRequest("Is there a recycling center?"), requestWithCity("testcity"));
        assertEquals(502, raw.getStatus().getCode());
        Object body = raw.getBody().orElseThrow();
        assertInstanceOf(OpenRouterPublicDto.class, body);
        OpenRouterPublicDto publicBody = (OpenRouterPublicDto) body;
        assertNotNull(publicBody);
        assertFalse(publicBody.isSuccess());
        assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), publicBody.getErrorCode());
    }

    // -------------------------------------------------------------------------
    // redact() — sync path
    // -------------------------------------------------------------------------

    @Test
    void redact_success_returns200() {
        // No identity → sync path → SQS/S3 must not be called.
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), ASSERT_NOT_CALLED_PUBLISHER,
                ASSERT_NOT_CALLED_UPLOADER, null);
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport"), requestWithCity("testcity"));
        assertEquals(200, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertEquals("Redacted letter", body.getMessage());
    }

    @Test
    void redact_refuse_returns422() {
        OpenRouterController c = new OpenRouterController(new FakeServiceRefuse(), ASSERT_NOT_CALLED_PUBLISHER,
                ASSERT_NOT_CALLED_UPLOADER, null);
        HttpResponse<?> raw = c.redact(new RedactRequest("How to cook paella?"), requestWithCity("testcity"));
        assertEquals(422, raw.getStatus().getCode());
    }

    @Test
    void redact_upstream_returns502() {
        OpenRouterController c = new OpenRouterController(new FakeServiceUpstream(), ASSERT_NOT_CALLED_PUBLISHER,
                ASSERT_NOT_CALLED_UPLOADER, null);
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport"), requestWithCity("testcity"));
        assertEquals(502, raw.getStatus().getCode());
    }

    @Test
    void redact_unsupportedFormat_returns400WithClearMessage() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), ASSERT_NOT_CALLED_PUBLISHER,
                ASSERT_NOT_CALLED_UPLOADER, null);
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport", null), requestWithCity("testcity"));
        assertEquals(400, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), body.getErrorCode());
        assertTrue(body.getError().contains("pdf"));
    }

    @Test
    void redact_anonymousRequest_returns400() {
        OpenRouterController c = new OpenRouterController(new FakeServiceRejectAnonymous(), ASSERT_NOT_CALLED_PUBLISHER,
                ASSERT_NOT_CALLED_UPLOADER, null);
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport. I want to be anonymous."),
                requestWithCity("testcity"));
        assertEquals(400, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertTrue(body.getError().contains("Anonymous"));
    }

    @Test
    void redact_missingIdentity_returns200WithQuestion() {
        OpenRouterController c = new OpenRouterController(new FakeServiceRequestsIdentity(),
                ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER, null);
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport"), requestWithCity("testcity"));
        assertEquals(200, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertTrue(body.getMessage().contains("first name"));
    }

    // -------------------------------------------------------------------------
    // redact() — async path (complete identity + PDF format)
    // -------------------------------------------------------------------------

    @Test
    void redact_completeIdentityAndPdfFormat_returns202WithPdfUrl() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), NOOP_PUBLISHER, FIXED_URL_UPLOADER,
                null);
        RedactRequest req = RedactRequest.fromJson("Noise from the airport", "pdf", null, "Joan", "Garcia",
                "12345678A");
        HttpResponse<?> raw = c.redact(req, requestWithCity("testcity"));
        assertEquals(202, raw.getStatus().getCode());
        RedactAcceptedDto body = (RedactAcceptedDto) raw.getBody().get();
        assertNotNull(body);
        assertTrue(body.success());
        assertNotNull(body.pdfUrl());
        assertTrue(body.pdfUrl().contains("complaint.pdf"), "pdfUrl must point to a PDF key");
        assertEquals(OpenRouterErrorCode.NONE.getCode(), body.errorCode());
    }

    @Test
    void redact_asyncPath_sqsPublishFailure_returns500() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), FAILING_PUBLISHER,
                FIXED_URL_UPLOADER, null);
        RedactRequest req = RedactRequest.fromJson("Noise from the airport", "pdf", null, "Joan", "Garcia",
                "12345678A");
        HttpResponse<?> raw = c.redact(req, requestWithCity("testcity"));
        assertEquals(500, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertEquals(OpenRouterErrorCode.INTERNAL.getCode(), body.getErrorCode());
    }

    @Test
    void redact_asyncPath_validationFailure_returns400() {
        // Anonymity check is run before SQS publish; result must be 400, not 202.
        OpenRouterController c = new OpenRouterController(new FakeServiceRejectAnonymous(), ASSERT_NOT_CALLED_PUBLISHER,
                FIXED_URL_UPLOADER, null);
        // Complete identity BUT anonymous request — validation must short-circuit.
        RedactRequest req = RedactRequest.fromJson("I want to be anonymous.", "pdf", null, "Joan", "Garcia",
                "12345678A");
        HttpResponse<?> raw = c.redact(req, requestWithCity("testcity"));
        assertEquals(400, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), body.getErrorCode());
    }

    @Test
    void redact_jsonFormat_returns400() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), ASSERT_NOT_CALLED_PUBLISHER,
                ASSERT_NOT_CALLED_UPLOADER, null);
        RedactRequest req = RedactRequest.fromJson("Noise from the airport", "json", null, null, null, null);
        HttpResponse<?> raw = c.redact(req, requestWithCity("testcity"));
        assertEquals(400, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), body.getErrorCode());
    }

    @Test
    void redact_autoFormat_returns400() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), ASSERT_NOT_CALLED_PUBLISHER,
                ASSERT_NOT_CALLED_UPLOADER, null);
        RedactRequest req = RedactRequest.fromJson("Noise from the airport", "auto", null, null, null, null);
        HttpResponse<?> raw = c.redact(req, requestWithCity("testcity"));
        assertEquals(400, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), body.getErrorCode());
    }

    @Test
    void redact_nullFormat_defaultsToPdfAndRoutes202() {
        OpenRouterController c = new OpenRouterController(new FakeServiceSuccess(), NOOP_PUBLISHER, FIXED_URL_UPLOADER,
                null);
        RedactRequest req = RedactRequest.fromJson("Noise from the airport", null, null, "Joan", "Garcia", "12345678A");
        HttpResponse<?> raw = c.redact(req, requestWithCity("testcity"));
        assertEquals(202, raw.getStatus().getCode());
        RedactAcceptedDto body = (RedactAcceptedDto) raw.getBody().get();
        assertNotNull(body);
        assertTrue(body.success());
        assertNotNull(body.pdfUrl());
        assertEquals(OpenRouterErrorCode.NONE.getCode(), body.errorCode());
    }

    // -------------------------------------------------------------------------
    // redact() — X-Identity-Token (OIDC identity verification, Option C)
    // -------------------------------------------------------------------------

    /** Validator stub that always returns the supplied verified identity. */
    static OidcIdentityTokenValidator validatorThatReturns(VerifiedCitizenIdentity identity) {
        // Use the protected test constructor — no DI, no JWKS fetch.
        // "testcity" must match the city attribute set in
        // requestWithCityAndIdentityToken().
        return new OidcIdentityTokenValidator("testcity", null, "sub") {
            @Override
            public VerifiedCitizenIdentity validate(String token, String cityId) {
                return identity;
            }
        };
    }

    /** Validator stub that always throws the supplied exception. */
    static OidcIdentityTokenValidator validatorThatThrows(String reason) {
        // "testcity" must match the city attribute set in
        // requestWithCityAndIdentityToken().
        return new OidcIdentityTokenValidator("testcity", null, "sub") {
            @Override
            public VerifiedCitizenIdentity validate(String token, String cityId) {
                throw new IdentityTokenValidationException(reason);
            }
        };
    }

    @Test
    void redact_validIdentityToken_overridesBodyFields() {
        // X-Identity-Token present + valid: IdP-verified identity is used.
        // Body fields (name/surname/idNumber) are absent — they must be ignored because
        // the token already provides a complete identity, triggering the async PDF
        // path.
        VerifiedCitizenIdentity idpIdentity = new VerifiedCitizenIdentity("Joan", "Torres", "12345678A");
        OidcIdentityTokenValidator validator = validatorThatReturns(idpIdentity);

        OpenRouterController c = new OpenRouterController(
                new FakeServiceSuccess(), NOOP_PUBLISHER, FIXED_URL_UPLOADER, validator);

        // No identity in the body — the token fills it in.
        HttpRequest<?> req = requestWithCityAndIdentityToken("testcity", "valid.token.here");
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport", OutputFormat.PDF, null), req);

        // Identity was complete after token validation → async path → 202
        assertEquals(202, raw.getStatus().getCode());
        RedactAcceptedDto body = (RedactAcceptedDto) raw.getBody().get();
        assertNotNull(body);
        assertTrue(body.success());
        assertNotNull(body.pdfUrl());
    }

    @Test
    void redact_invalidIdentityToken_returns401() {
        // X-Identity-Token present but invalid → 401, request rejected before any
        // service call.
        OidcIdentityTokenValidator validator = validatorThatThrows("Identity token has expired");

        OpenRouterController c = new OpenRouterController(
                new FakeServiceSuccess(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER, validator);

        HttpRequest<?> req = requestWithCityAndIdentityToken("testcity", "expired.token.here");
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport"), req);

        assertEquals(401, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertEquals(OpenRouterErrorCode.UNAUTHORIZED.getCode(), body.getErrorCode());
        assertTrue(body.getError().contains("re-authenticate"));
    }

    @Test
    void redact_identityTokenPresentButFeatureDisabled_tokenIgnored() {
        // validator == null (flag off): X-Identity-Token header must be silently
        // ignored.
        // The request has no body identity fields either → sync path (AI asks for
        // identity).
        OpenRouterController c = new OpenRouterController(
                new FakeServiceRequestsIdentity(), ASSERT_NOT_CALLED_PUBLISHER, ASSERT_NOT_CALLED_UPLOADER, null);

        HttpRequest<?> req = requestWithCityAndIdentityToken("testcity", "some.token.here");
        HttpResponse<?> raw = c.redact(new RedactRequest("Noise from the airport"), req);

        // Feature is off → token ignored → no identity → sync path asks for identity
        assertEquals(200, raw.getStatus().getCode());
        OpenRouterPublicDto body = (OpenRouterPublicDto) raw.getBody().get();
        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertTrue(body.getMessage().contains("first name"));
    }

    @Test
    void redact_noIdentityTokenHeaderAndFeatureEnabled_usesBodyFields() {
        // Feature is on but no X-Identity-Token header → falls back to body fields
        // normally.
        VerifiedCitizenIdentity idpIdentity = new VerifiedCitizenIdentity("Joan", "Torres", "12345678A");
        OidcIdentityTokenValidator validator = validatorThatReturns(idpIdentity);

        OpenRouterController c = new OpenRouterController(
                new FakeServiceSuccess(), NOOP_PUBLISHER, FIXED_URL_UPLOADER, validator);

        // Body has complete identity, no X-Identity-Token header, PDF format → async
        // path
        RedactRequest bodyReq = RedactRequest.fromJson("Noise", "pdf", null, "Maria", "Garcia", "87654321B");
        HttpResponse<?> raw = c.redact(bodyReq, requestWithCity("testcity"));

        assertEquals(202, raw.getStatus().getCode());
        RedactAcceptedDto body = (RedactAcceptedDto) raw.getBody().get();
        assertNotNull(body);
        assertTrue(body.success());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a POST request with the city attribute pre-set on it, simulating what
     * JwtAuthFilter does after successful JWT validation.
     */
    private static HttpRequest<?> requestWithCity(String cityId) {
        MutableHttpRequest<?> req = HttpRequest.POST("/complai/redact", "{}");
        req.setAttribute(JwtAuthFilter.CITY_ATTRIBUTE, cityId);
        return req;
    }

    /**
     * Creates a POST request with the city attribute and an X-Identity-Token
     * header,
     * simulating a frontend that has authenticated the citizen via
     * VALId/Cl@ve/idCat.
     */
    private static HttpRequest<?> requestWithCityAndIdentityToken(String cityId, String identityToken) {
        MutableHttpRequest<?> req = HttpRequest.POST("/complai/redact", "{}");
        req.setAttribute(JwtAuthFilter.CITY_ATTRIBUTE, cityId);
        req.header("X-Identity-Token", identityToken);
        return req;
    }
}
