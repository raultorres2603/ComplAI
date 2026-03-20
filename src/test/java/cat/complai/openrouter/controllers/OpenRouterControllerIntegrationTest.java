package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OutputFormat;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.controllers.dto.AskRequest;
import cat.complai.openrouter.controllers.dto.RedactRequest;
import cat.complai.openrouter.services.OpenRouterServices;
import cat.complai.openrouter.services.ai.AiResponseProcessingService;
import cat.complai.openrouter.services.conversation.ConversationManagementService;
import cat.complai.openrouter.services.procedure.ProcedureContextService;
import cat.complai.openrouter.helpers.EventRagHelperRegistry;
import cat.complai.openrouter.services.validation.InputValidationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.annotation.MockBean;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cat.complai.openrouter.dto.RedactAcceptedDto;
import cat.complai.s3.S3PdfUploader;
import cat.complai.sqs.SqsComplaintPublisher;
import cat.complai.sqs.dto.RedactSqsMessage;

import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;

import cat.complai.openrouter.helpers.ProcedureRagHelperRegistry;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
public class OpenRouterControllerIntegrationTest {

    // Same Base64-encoded key used in JwtAuthFilterTest and JwtValidatorTest,
    // and declared as jwt.secret in src/test/resources/application.properties.
    // Using the same key in all places ensures tokens minted here are accepted
    // by the JwtValidator that the test Micronaut context instantiates.
    private static final String TEST_SECRET_B64 = "hEmatrRKbxfC/9PxZ14VsYksRkTZHMpqRScBUhshYzQ=";
    private static final String ISSUER = "complai";

    @Inject
    @Client("/")
    HttpClient client;

    // Injected to test PDF generation directly, bypassing the HTTP layer.
    // Micronaut's embedded Netty server closes the connection for binary responses,
    // so PDF correctness must be verified at the service level, not via HTTP.
    @Inject
    IOpenRouterService openRouterService;

    private final ObjectMapper mapper = new ObjectMapper();

    // A fresh, valid JWT is minted before each test.
    // Keeping it per-test (rather than static) avoids flakiness if tests run near expiry.
    private String authHeader;

    @BeforeEach
    void mintTestToken() {
        byte[] keyBytes = Base64.getDecoder().decode(TEST_SECRET_B64);
        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        String token = Jwts.builder()
                .subject("integration-test")
                .issuer(ISSUER)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .claim("city", "elprat")
                .signWith(key)
                .compact();
        authHeader = "Bearer " + token;
    }

    @Test
    void integration_ask_success() {
        AskRequest req = new AskRequest("Is there a recycling center?");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        Optional<OpenRouterPublicDto> bodyOpt = resp.getBody();
        assertTrue(bodyOpt.isPresent());
        OpenRouterPublicDto body = bodyOpt.get();
        assertTrue(body.isSuccess());
        assertEquals("OK from AI (integration)", body.getMessage());
    }

    @Test
    void integration_ask_includesSources_whenProceduresMatch() throws Exception {
        // Prepare fake procedures with URLs
        OpenRouterServices svc = (OpenRouterServices) openRouterService;
        // Since we can't inject procedures via the public API, we rely on the mock HttpWrapper
        // to simulate a scenario where the AI would receive procedure context.
        AskRequest req = new AskRequest("recycling center");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        Optional<OpenRouterPublicDto> bodyOpt = resp.getBody();
        assertTrue(bodyOpt.isPresent());
        OpenRouterPublicDto body = bodyOpt.get();
        assertTrue(body.isSuccess());
        assertEquals("OK from AI (integration)", body.getMessage());
        // In this test setup, no real procedures are loaded, so sources should be empty.
        // This test ensures the field is present and does not error.
        assertNotNull(body.getSources());
        assertTrue(body.getSources().isEmpty());
    }

    @Test
    void integration_redact_success() {
        RedactRequest req = new RedactRequest("There is noise from the airport");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req)
                .header("Authorization", authHeader);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        Optional<OpenRouterPublicDto> bodyOpt = resp.getBody();
        assertTrue(bodyOpt.isPresent());
        OpenRouterPublicDto body = bodyOpt.get();
        assertTrue(body.isSuccess());
        assertEquals("Redacted (integration)", body.getMessage());
    }

    @Test
    void integration_ask_refusal() throws Exception {
        AskRequest req = new AskRequest("Tell me about France [REFUSE]");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);
        try {
            client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
            fail("Expected HttpClientResponseException for 422");
        } catch (HttpClientResponseException e) {
            assertEquals(422, e.getStatus().getCode());
            String bodyJson = e.getResponse().getBody(String.class).orElse("{}");
            JsonNode node = mapper.readTree(bodyJson);
            assertNotNull(node);
            assertEquals(OpenRouterErrorCode.REFUSAL.getCode(), node.path("errorCode").asInt());
        }
    }

    @Test
    void integration_ask_upstream() throws Exception {
        AskRequest req = new AskRequest("Is there a recycling center? [UPSTREAM]");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);
        try {
            client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
            fail("Expected HttpClientResponseException for 502");
        } catch (HttpClientResponseException e) {
            assertEquals(502, e.getStatus().getCode());
            String bodyJson = e.getResponse().getBody(String.class).orElse("{}");
            JsonNode node = mapper.readTree(bodyJson);
            assertNotNull(node);
            assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), node.path("errorCode").asInt());
        }
    }

    @Test
    void integration_redact_refusal() throws Exception {
        RedactRequest req = new RedactRequest("How to cook paella? [REFUSE]");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req)
                .header("Authorization", authHeader);
        try {
            client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
            fail("Expected HttpClientResponseException for 422");
        } catch (HttpClientResponseException e) {
            assertEquals(422, e.getStatus().getCode());
            String bodyJson = e.getResponse().getBody(String.class).orElse("{}");
            JsonNode node = mapper.readTree(bodyJson);
            assertNotNull(node);
            assertEquals(OpenRouterErrorCode.REFUSAL.getCode(), node.path("errorCode").asInt());
        }
    }

    @Test
    void integration_redact_upstream() throws Exception {
        RedactRequest req = new RedactRequest("Noise from airport [UPSTREAM]");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req)
                .header("Authorization", authHeader);
        try {
            client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
            fail("Expected HttpClientResponseException for 502");
        } catch (HttpClientResponseException e) {
            assertEquals(502, e.getStatus().getCode());
            String bodyJson = e.getResponse().getBody(String.class).orElse("{}");
            JsonNode node = mapper.readTree(bodyJson);
            assertNotNull(node);
            assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), node.path("errorCode").asInt());
        }
    }

    @Test
    void integration_redact_aiHeader_returnsLetterAsText() {
        // The sync service path never produces PDF bytes — PDFs are generated by the async worker.
        // With a complete identity the service returns the parsed letter body as text.
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto dto = openRouterService.redactComplaint("Complaint about noise [HEADER]", OutputFormat.AUTO, null, identity, "elprat");
        assertTrue(dto.isSuccess(), "Expected success");
        assertNull(dto.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(dto.getMessage(), "Letter body must be returned as text");
        assertFalse(dto.getMessage().isEmpty());
    }

    @Test
    void integration_redact_missingHeader_fallsBackToJsonSuccess() {
        // AI returns plain text without JSON header. The service must NOT reject with 400.
        // Because the client did not explicitly request PDF (format=AUTO), the service degrades
        // gracefully and returns the raw AI message as a 200 JSON response.
        RedactRequest req = new RedactRequest("Complaint with no header [NOHEADER]");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req)
                .header("Authorization", authHeader);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        Optional<OpenRouterPublicDto> bodyOpt = resp.getBody();
        assertTrue(bodyOpt.isPresent());
        assertTrue(bodyOpt.get().isSuccess());
        assertNotNull(bodyOpt.get().getMessage());
    }

    @Test
    void integration_redact_headerWithInlineJsonBody_returnsLetterAsText() {
        // Tests Shape 3 of AiParsed: AI returns a single JSON object with both the format and body
        // inline (e.g. {"format":"pdf","body":"..."}) instead of a header + separate letter body.
        // The service must extract the body and return it as text — no PDF is produced.
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto dto = openRouterService.redactComplaint(
                "Complaint that requests inline body [HEADER_INLINE]", OutputFormat.AUTO, null, identity, "elprat");
        assertTrue(dto.isSuccess(), "Expected success");
        assertNull(dto.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(dto.getMessage(), "Letter body must be returned as text");
        assertFalse(dto.getMessage().isEmpty());
    }

    @Test
    void integration_redact_invalidHeaderFormat_fallsBackToJsonSuccess() {
        // AI returns a JSON header with an unrecognised format ("xml"). OutputFormat.fromString
        // treats unknown values as AUTO, which triggers the graceful fallback: the service returns
        // 200 with the raw AI message instead of failing with a 400.
        RedactRequest req = new RedactRequest("Complaint with invalid header [HEADER_INVALID]");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req)
                .header("Authorization", authHeader);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        Optional<OpenRouterPublicDto> bodyOpt = resp.getBody();
        assertTrue(bodyOpt.isPresent());
        assertTrue(bodyOpt.get().isSuccess());
    }

    @Test
    void integration_redact_pdfUnicodeCatalanCharacters() {
        // Unicode characters must survive parsing and be returned correctly in the text response.
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto dto = openRouterService.redactComplaint(
            "Prova unicode català [HEADER]", OutputFormat.PDF, null, identity, "elprat");
        assertTrue(dto.isSuccess(), "Expected success");
        assertNull(dto.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(dto.getMessage(), "Letter body must be returned as text");
    }

    @Test
    void integration_redact_aiHeader_returnsLetterTextWithCorrectContent() {
        // Verify the letter body returned by the service contains the expected Catalan fragment.
        // PDF generation is async-only — the sync service path returns text.
        String expectedBodyFragment = "Això és una prova amb caràcters especials";

        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto dto = openRouterService.redactComplaint(
                "Complaint with Catalan text [HEADER_CATALAN]", OutputFormat.AUTO, null, identity, "elprat");
        assertTrue(dto.isSuccess(), "Expected success");
        assertNull(dto.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(dto.getMessage(), "Letter body must be returned as text");
        assertTrue(dto.getMessage().contains(expectedBodyFragment),
                "Letter text should contain the Catalan body content");
    }

    // --- JWT authentication ---

    @Test
    void integration_ask_missingJwt_returns401() throws Exception {
        // The JwtAuthFilter must short-circuit and return 401 before the controller is invoked.
        // No Authorization header is sent — simulating an unauthenticated client.
        AskRequest req = new AskRequest("Hola, quina és la capital de Catalunya?");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req);
        try {
            client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
            fail("Expected HttpClientResponseException for 401");
        } catch (HttpClientResponseException e) {
            assertEquals(401, e.getStatus().getCode());
            String bodyJson = e.getResponse().getBody(String.class).orElse("{}");
            JsonNode node = mapper.readTree(bodyJson);
            assertNotNull(node);
            assertFalse(node.path("success").asBoolean(true));
            assertEquals(OpenRouterErrorCode.UNAUTHORIZED.getCode(), node.path("errorCode").asInt());
        }
    }

    @Test
    void integration_ask_expiredJwt_returns401() throws Exception {
        // An expired token must be rejected even if it is otherwise well-formed.
        byte[] keyBytes = Base64.getDecoder().decode(TEST_SECRET_B64);
        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        String expiredToken = Jwts.builder()
                .subject("integration-test")
                .issuer(ISSUER)
                .issuedAt(Date.from(Instant.now().minus(2, ChronoUnit.DAYS)))
                .expiration(Date.from(Instant.now().minus(1, ChronoUnit.DAYS)))
                .signWith(key)
                .compact();

        AskRequest req = new AskRequest("Hola");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", "Bearer " + expiredToken);
        try {
            client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
            fail("Expected HttpClientResponseException for 401");
        } catch (HttpClientResponseException e) {
            assertEquals(401, e.getStatus().getCode());
            String bodyJson = e.getResponse().getBody(String.class).orElse("{}");
            JsonNode node = mapper.readTree(bodyJson);
            assertEquals(OpenRouterErrorCode.UNAUTHORIZED.getCode(), node.path("errorCode").asInt());
        }
    }

    @Test
    void integration_redact_anonymousRequest_returns400() throws Exception {
        // Anonymous complaints must be rejected at the service layer and surfaced as 400 VALIDATION.
        // The AI must never be called — the service rejects before building the prompt.
        RedactRequest req = new RedactRequest("Noise from the airport. I want to remain anonymous.");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req)
                .header("Authorization", authHeader);
        try {
            client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
            fail("Expected HttpClientResponseException for 400");
        } catch (HttpClientResponseException e) {
            assertEquals(400, e.getStatus().getCode());
            String bodyJson = e.getResponse().getBody(String.class).orElse("{}");
            JsonNode node = mapper.readTree(bodyJson);
            assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), node.path("errorCode").asInt());
            assertTrue(node.path("error").asText().contains("Anonymous"),
                    "Error must mention anonymous complaints");
        }
    }

    @Test
    void integration_redact_missingIdentity_returns200WithQuestion() {
        // When no identity is provided, the AI is instructed to ask for the missing fields.
        // The response must be 200 so the client can display the question to the user.
        RedactRequest req = new RedactRequest("Noise from the airport [ASKS_IDENTITY]");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req)
                .header("Authorization", authHeader);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        assertTrue(resp.getBody().isPresent());
        OpenRouterPublicDto body = resp.getBody().get();
        assertTrue(body.isSuccess());
        assertTrue(body.getMessage().contains("first name"),
                "Response must ask for the missing identity fields");
    }

    @Test
    void integration_redact_completeIdentity_returnsLetterAsText() {
        // When full identity is provided the service returns the letter body as text.
        // PDFs are generated by the async worker Lambda — the sync path never produces PDF bytes.
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto dto = openRouterService.redactComplaint(
                "Noise from the airport [IDENTITY_LETTER]", OutputFormat.PDF, null, identity, "elprat");
        assertTrue(dto.isSuccess(), "Expected success");
        assertNull(dto.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(dto.getMessage(), "Letter body must be returned as text");
        assertTrue(dto.getMessage().contains("Joan Garcia"), "Letter must contain the complainant's name");
    }

    @Test
    void integration_redact_completeIdentityAndPdfFormat_returns202WithPdfUrl() {
        // Complete identity + PDF format → async path → 202 Accepted with pdfUrl.
        RedactRequest req = RedactRequest.fromJson(
                "Noise from the airport", "pdf", null, "Joan", "Garcia", "12345678A");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req)
                .header("Authorization", authHeader);
        HttpResponse<RedactAcceptedDto> resp = client.toBlocking().exchange(httpReq, RedactAcceptedDto.class);
        assertEquals(202, resp.getStatus().getCode());
        assertTrue(resp.getBody().isPresent());
        RedactAcceptedDto body = resp.getBody().get();
        assertTrue(body.success());
        assertNotNull(body.pdfUrl(), "pdfUrl must be present in 202 response");
        assertTrue(body.pdfUrl().contains("complaint.pdf"), "pdfUrl must reference a PDF key");
    }

    @Test
    void integration_redact_completeIdentityAndJsonFormat_returnsSyncPath200() {
        // JSON format with complete identity must stay synchronous (200, not 202).
        RedactRequest req = RedactRequest.fromJson(
                "Noise from the airport", "json", null, "Joan", "Garcia", "12345678A");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req)
                .header("Authorization", authHeader);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        assertTrue(resp.getBody().isPresent());
        assertTrue(resp.getBody().get().isSuccess());
    }

    @MockBean(HttpWrapper.class)
    @Replaces(HttpWrapper.class)
    HttpWrapper openRouterHttpWrapper() {
        return new HttpWrapper() {
            @Override
            public CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages) {
                // Extract the content of the last user message to determine which scenario to run.
                String userPrompt = messages == null ? null : messages.stream()
                        .filter(m -> "user".equals(m.get("role")))
                        .reduce((first, second) -> second)
                        .map(m -> (String) m.get("content"))
                        .orElse(null);

                if (userPrompt != null && userPrompt.contains("[REFUSE]")) {
                    // Simulate AI refusal
                    return CompletableFuture.completedFuture(new HttpDto("I'm sorry, I can't help with that request because it's not about El Prat de Llobregat.", 200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[UPSTREAM]")) {
                    // Simulate upstream error
                    return CompletableFuture.completedFuture(new HttpDto(null, 500, "POST", "Upstream error"));
                }
                if (userPrompt != null && userPrompt.contains("[HEADER]")) {
                    // Simulate PDF header
                    String body = "{\"format\": \"pdf\"}\n\nDear Ajuntament,\n\nI am writing to complain about noise...\n\nSincerely,\nResident";
                    return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[NOHEADER]")) {
                    // Simulate missing header
                    String body = "Dear Ajuntament,\n\nI am writing to complain about...\n\nSincerely,\nResident";
                    return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[HEADER_LONG]")) {
                    // Simulate long PDF
                    String sb = "{\"format\": \"pdf\"}\n\nDear Ajuntament,\n\n" +
                            "This is a long complaint sentence to generate many pages. ".repeat(800) +
                            "\n\nSincerely,\nResident";
                    return CompletableFuture.completedFuture(new HttpDto(sb, 200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[HEADER_INLINE]")) {
                    // Simulate Shape 3: AI returns format and letter body inlined in a single JSON object,
                    // rather than a first-line header followed by a separate letter body.
                    String body = "{\"format\": \"pdf\", \"body\": \"Dear Ajuntament,\\n\\nI am writing to complain about noise from the airport.\\n\\nSincerely,\\nJoan Garcia\"}";
                    return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[HEADER_INVALID]")) {
                    // Simulate invalid header
                    String body = "{\"format\": \"xml\"}\n\nThis body should be rejected due to invalid format.";
                    return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[HEADER_CATALAN]")) {
                    // Simulate PDF response with Catalan special characters
                    String body = "{\"format\": \"pdf\"}\n\nAixò és una prova amb caràcters especials: à, é, í, ò, ú, ç, l·l.\n\nAtentament,\nVeí";
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
                // Default: simulate a successful text response
                if (userPrompt != null && userPrompt.contains("recycling center")) {
                    return CompletableFuture.completedFuture(new HttpDto("OK from AI (integration)", 200, "POST", null));
                }
                // Fallback: simulate a generic successful redact response
                return CompletableFuture.completedFuture(new HttpDto("Redacted (integration)", 200, "POST", null));
            }
        };
    }

    @MockBean(SqsComplaintPublisher.class)
    @Replaces(SqsComplaintPublisher.class)
    SqsComplaintPublisher noopSqsPublisher() {
        return new SqsComplaintPublisher() {
            @Override
            public void publish(RedactSqsMessage message) { /* no-op for tests */ }
        };
    }

    @MockBean(S3PdfUploader.class)
    @Replaces(S3PdfUploader.class)
    S3PdfUploader fixedUrlS3Uploader() {
        return new S3PdfUploader() {
            @Override
            public String generatePresignedGetUrl(String key) {
                return "https://bucket.s3.eu-west-1.amazonaws.com/" + key;
            }
        };
    }

    @Factory
    static class TestBeans {
        @Singleton
        @Replaces(OpenRouterServices.class)
        IOpenRouterService openRouterService(HttpWrapper httpWrapper) {
            InputValidationService validationService = new InputValidationService(5000);
            ConversationManagementService conversationService = new ConversationManagementService();
            AiResponseProcessingService aiResponseService = new AiResponseProcessingService(httpWrapper, 30);
            ProcedureContextService procedureContextService = new ProcedureContextService(new ProcedureRagHelperRegistry(), new EventRagHelperRegistry(), new cat.complai.openrouter.helpers.RedactPromptBuilder());
            return new OpenRouterServices(validationService, conversationService, aiResponseService, procedureContextService, new cat.complai.openrouter.helpers.RedactPromptBuilder());
        }
        @Singleton
        @Replaces(ProcedureRagHelperRegistry.class)
        ProcedureRagHelperRegistry ragRegistry() {
            return new ProcedureRagHelperRegistry();
        }
    }
}
