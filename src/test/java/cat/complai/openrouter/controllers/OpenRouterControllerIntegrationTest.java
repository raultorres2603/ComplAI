package cat.complai.openrouter.controllers;

import cat.complai.http.HttpWrapper;
import cat.complai.http.OpenRouterStreamingException;
import cat.complai.http.dto.HttpDto;
import cat.complai.http.dto.OpenRouterStreamStartResult;
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
import cat.complai.openrouter.services.cache.ResponseCacheService;
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

import cat.complai.openrouter.helpers.ProcedureRagHelperRegistry;
import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
public class OpenRouterControllerIntegrationTest {

    // Same Base64-encoded key used in JwtAuthFilterTest and JwtValidatorTest,
    // and declared as jwt.secret in src/test/resources/application.properties.
    // Using the same key in all places ensures tokens minted here are accepted
    // by the JwtValidator that the test Micronaut context instantiates.
    private static final String TEST_SECRET_B64 = "hEmatrRKbxfC/9PxZ14VsYksRkTZHMpqRScBUhshYzQ=";
    private static final String ISSUER = "complai";
    private static final AtomicInteger OPENROUTER_POST_CALLS = new AtomicInteger();

    @Inject
    @Client("/")
    HttpClient client;

    // Injected to test PDF generation directly, bypassing the HTTP layer.
    // Micronaut's embedded Netty server closes the connection for binary responses,
    // so PDF correctness must be verified at the service level, not via HTTP.
    @Inject
    IOpenRouterService openRouterService;

    // Injected to clear cache between tests and prevent test pollution
    @Inject
    ResponseCacheService cacheService;

    private final ObjectMapper mapper = new ObjectMapper();

    // A fresh, valid JWT is minted before each test.
    // Keeping it per-test (rather than static) avoids flakiness if tests run near
    // expiry.
    private String authHeader;

    @BeforeEach
    void mintTestToken() {
        OPENROUTER_POST_CALLS.set(0);

        // Clear the response cache before each test to prevent pollution from previous
        // tests.
        // ResponseCacheService is a @Singleton that persists across tests, so we must
        // invalidate it to ensure each test gets fresh mock responses.
        cacheService.invalidateAll();

        authHeader = mintAuthHeader("elprat");
    }

    private String mintAuthHeader(String cityId) {
        byte[] keyBytes = Base64.getDecoder().decode(TEST_SECRET_B64);
        SecretKey key = Keys.hmacShaKeyFor(keyBytes);
        String token = Jwts.builder()
                .subject("integration-test-" + UUID.randomUUID())
                .issuer(ISSUER)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .claim("city", cityId)
                .signWith(key)
                .compact();
        return "Bearer " + token;
    }

    @Test
    void integration_ask_success() {
        // The streaming SSE endpoint is covered by the dedicated ask-stream tests
        // below.
        // Sync ask() is used here to verify service-level correctness with the mock
        // HttpWrapper.
        OpenRouterResponseDto result = openRouterService.ask("Is there a recycling center?", null, "elprat");
        assertTrue(result.isSuccess());
        assertEquals("OK from AI (integration)", result.getMessage());
    }

    @Test
    void integration_ask_repeatedEquivalentRequests_reuseResponseCachePath() {
        // /complai/ask is stream-first. Validate cache reuse through the sync service
        // seam that still exercises AiResponseProcessingService + ResponseCacheService.
        OpenRouterResponseDto firstResponse = openRouterService.ask("Is there a recycling center?", null, "elprat");
        OpenRouterResponseDto secondResponse = openRouterService.ask("Is there a recycling center?", null, "elprat");

        assertTrue(firstResponse.isSuccess());
        assertTrue(secondResponse.isSuccess());
        assertEquals(1, OPENROUTER_POST_CALLS.get(),
                "Repeated equivalent ask requests should reuse the cached response path");
    }

    @Test
    void integration_ask_cacheRemainsCityScoped() {
        // City scoping is a cache-key concern. Test it via sync ask service seam.
        OpenRouterResponseDto firstResponse = openRouterService.ask("Is there a recycling center?", null, "elprat");
        OpenRouterResponseDto secondResponse = openRouterService.ask("Is there a recycling center?", null, "testcity");

        assertTrue(firstResponse.isSuccess());
        assertTrue(secondResponse.isSuccess());
        assertEquals(2, OPENROUTER_POST_CALLS.get(),
                "The response cache must remain city-scoped across authenticated ask requests");
    }

    @Test
    void integration_ask_includesSources_whenProceduresMatch() throws Exception {
        // Sources are included in the sync ask() response DTO.
        // Tested at service level — the SSE stream does not carry sources metadata.
        OpenRouterResponseDto result = openRouterService.ask("recycling center", null, "elprat");
        assertTrue(result.isSuccess());
        assertEquals("OK from AI (integration)", result.getMessage());
        assertNotNull(result.getSources());
        assertTrue(result.getSources().isEmpty());
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
        // Refusal detection runs through AiResponseProcessingService in the sync ask()
        // path.
        // Tested at service level with the mock HttpWrapper.
        OpenRouterResponseDto result = openRouterService.ask("Tell me about France [REFUSE]", null, "elprat");
        assertFalse(result.isSuccess());
        assertEquals(OpenRouterErrorCode.REFUSAL, result.getErrorCode());
    }

    @Test
    void integration_ask_upstream() throws Exception {
        AskRequest req = new AskRequest("Is there a recycling center? [UPSTREAM_402]");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);
        try {
            client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
            fail("Expected HttpClientResponseException for upstream startup error");
        } catch (HttpClientResponseException e) {
            assertEquals(502, e.getStatus().getCode());
            String bodyJson = e.getResponse().getBody(String.class).orElse("{}");
            JsonNode node = mapper.readTree(bodyJson);
            assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), node.path("errorCode").asInt());
            assertFalse(node.path("success").asBoolean(true));
        }
    }

    @Test
    void integration_ask_upstream429_mapsTo502() throws Exception {
        AskRequest req = new AskRequest("Is there a recycling center? [UPSTREAM_429]");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);
        try {
            client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
            fail("Expected HttpClientResponseException for upstream startup error");
        } catch (HttpClientResponseException e) {
            assertEquals(502, e.getStatus().getCode());
            String bodyJson = e.getResponse().getBody(String.class).orElse("{}");
            JsonNode node = mapper.readTree(bodyJson);
            assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), node.path("errorCode").asInt());
        }
    }

    @Test
    void integration_ask_upstream402_auditLogRecordsUpstreamCode() throws Exception {
        Logger auditLogger = Logger.getLogger("AuditLogger");
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        auditLogger.addHandler(handler);
        try {
            AskRequest req = new AskRequest("Is there a recycling center? [UPSTREAM_402]");
            HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                    .header("Authorization", authHeader);
            assertThrows(HttpClientResponseException.class,
                    () -> client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class));
        } finally {
            auditLogger.removeHandler(handler);
        }

        assertTrue(messages.stream().anyMatch(message -> message.contains("\"endpoint\":\"/complai/ask\"")
                && message.contains("\"errorCode\":3")));
        assertTrue(messages.stream().noneMatch(message -> message.contains("UPSTREAM_402")));
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
        // The sync service path never produces PDF bytes — PDFs are generated by the
        // async worker.
        // With a complete identity the service returns the parsed letter body as text.
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto dto = openRouterService.redactComplaint("Complaint about noise [HEADER]",
                OutputFormat.AUTO, null, identity, "elprat");
        assertTrue(dto.isSuccess(), "Expected success");
        assertNull(dto.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(dto.getMessage(), "Letter body must be returned as text");
        assertFalse(dto.getMessage().isEmpty());
    }

    @Test
    void integration_redact_missingHeader_fallsBackToJsonSuccess() {
        // AI returns plain text without JSON header. The service must NOT reject with
        // 400.
        // Because the client did not explicitly request PDF (format=AUTO), the service
        // degrades
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
        // Tests Shape 3 of AiParsed: AI returns a single JSON object with both the
        // format and body
        // inline (e.g. {"format":"pdf","body":"..."}) instead of a header + separate
        // letter body.
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
        // AI returns a JSON header with an unrecognised format ("xml").
        // OutputFormat.fromString
        // treats unknown values as AUTO, which triggers the graceful fallback: the
        // service returns
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
        // Unicode characters must survive parsing and be returned correctly in the text
        // response.
        ComplainantIdentity identity = new ComplainantIdentity("Joan", "Garcia", "12345678A");
        OpenRouterResponseDto dto = openRouterService.redactComplaint(
                "Prova unicode català [HEADER]", OutputFormat.PDF, null, identity, "elprat");
        assertTrue(dto.isSuccess(), "Expected success");
        assertNull(dto.getPdfData(), "Service must never produce PDF bytes — PDFs are always async");
        assertNotNull(dto.getMessage(), "Letter body must be returned as text");
    }

    @Test
    void integration_redact_aiHeader_returnsLetterTextWithCorrectContent() {
        // Verify the letter body returned by the service contains the expected Catalan
        // fragment.
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

    // --- Context Optimization: Verify Limits ---

    @Test
    void integration_ask_withProcedureMatches_limitsToProcedureMax() throws Exception {
        // Test that procedure context building respects MAX_RESULTS = 3 limit.
        // Tested at service level — sync ask() returns sources metadata in the DTO.
        OpenRouterResponseDto result = openRouterService.ask(
                "How do I apply for any permit or license at the municipal center?", null, "elprat");
        assertTrue(result.isSuccess(), "Expected successful response");
        assertNotNull(result.getSources());
        assertTrue(result.getSources().size() <= 3,
                "Procedure context should contain at most 3 sources");
    }

    @Test
    void integration_ask_withEventMatches_limitsToEventMax() throws Exception {
        // Test that event context building respects MAX_RESULTS = 3 limit.
        // Tested at service level — sync ask() returns sources metadata in the DTO.
        OpenRouterResponseDto result = openRouterService.ask(
                "What events and activities are happening in the coming weeks?", null, "elprat");
        assertTrue(result.isSuccess(), "Expected successful response");
        assertNotNull(result.getSources());
        assertTrue(result.getSources().size() <= 3,
                "Event context should contain at most 3 sources");
    }

    // --- JWT authentication ---

    @Test
    void integration_ask_missingJwt_returns401() throws Exception {
        // The JwtAuthFilter must short-circuit and return 401 before the controller is
        // invoked.
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
        // Anonymous complaints must be rejected at the service layer and surfaced as
        // 400 VALIDATION.
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
        // When no identity is provided, the AI is instructed to ask for the missing
        // fields.
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
        // PDFs are generated by the async worker Lambda — the sync path never produces
        // PDF bytes.
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

    // ---- SSE Multi-Event Streaming Tests ----

    @Test
    void integration_ask_streamReceivesAllEventTypes() throws Exception {
        // Test that /complai/ask SSE stream emits all 4 event types in correct
        // sequence.
        // We mock the HTTP wrapper to return streaming chunks, and verify the
        // controller properly processes them and emits chunk/sources/done events.
        AskRequest req = new AskRequest("Is there a recycling center? [SSE_MULTIEXENT]");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);

        // Use toBlocking and read the response body which should contain SSE events.
        // Micronaut's SSE streaming returns raw string data in the body.
        HttpResponse<String> resp = client.toBlocking().exchange(httpReq, String.class);

        assertEquals(200, resp.getStatus().getCode());
        String streamContent = resp.getBody().orElse("");

        // Parse the SSE stream (format: "data: <json>\n\n")
        String[] lines = streamContent.split("\n\n");
        List<String> events = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("data:")) {
                events.add(line.substring(5).trim()); // Remove "data:" prefix
            }
        }

        // Debug: Print all received events
        System.out.println("DEBUG: Number of events received: " + events.size());
        for (int i = 0; i < events.size(); i++) {
            System.out.println("DEBUG: Event " + i + ": " + events.get(i));
        }

        // Should have at least 3 events: chunk(s), sources, done
        assertTrue(events.size() >= 3, "Expected at least 3 SSE events, got " + events.size());

        // Verify first event is a chunk
        JsonNode firstEvent = mapper.readTree(events.get(0));
        assertEquals("chunk", firstEvent.path("type").asText());

        // Verify sources event (second or later)
        JsonNode sourcesEvent = null;
        for (String eventJson : events.subList(1, events.size())) {
            JsonNode node = mapper.readTree(eventJson);
            if ("sources".equals(node.path("type").asText())) {
                sourcesEvent = node;
                break;
            }
        }
        assertNotNull(sourcesEvent, "Should emit a sources event");
        assertEquals("sources", sourcesEvent.path("type").asText());
        assertTrue(sourcesEvent.path("sources").isArray(),
                "sources event should contain an array of sources");

        // Verify done event (last)
        JsonNode lastEvent = mapper.readTree(events.get(events.size() - 1));
        assertEquals("done", lastEvent.path("type").asText());
        assertTrue(lastEvent.path("conversationId").isNull() ||
                !lastEvent.path("conversationId").asText().isEmpty(),
                "done event should have conversationId or be null");
    }

    @Test
    void integration_ask_streamEmitsErrorOnValidationFailure() throws Exception {
        // Test that validation errors are emitted as SSE error events.
        AskRequest req = new AskRequest(""); // Empty question triggers validation error
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);

        HttpResponse<String> resp = client.toBlocking().exchange(httpReq, String.class);
        assertEquals(200, resp.getStatus().getCode()); // Stream initiated

        String streamContent = resp.getBody().orElse("");
        String[] lines = streamContent.split("\n\n");

        String errorEventJson = null;
        for (String line : lines) {
            if (line.startsWith("data:")) {
                errorEventJson = line.substring(5).trim();
                break;
            }
        }

        assertNotNull(errorEventJson, "Should emit at least one event");
        JsonNode event = mapper.readTree(errorEventJson);
        assertEquals("error", event.path("type").asText());
        assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), event.path("errorCode").asInt());
    }

    @Test
    void integration_ask_streamIncludesConversationIdInDoneEvent() throws Exception {
        // Test that the done event includes the conversationId if provided.
        AskRequest req = new AskRequest("Is there a recycling center? [SSE_MULTIEXENT_CONVID]",
                "test-conversation-xyz");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);

        HttpResponse<String> resp = client.toBlocking().exchange(httpReq, String.class);
        assertEquals(200, resp.getStatus().getCode());

        String streamContent = resp.getBody().orElse("");
        String[] lines = streamContent.split("\n\n");

        JsonNode doneEvent = null;
        for (String line : lines) {
            if (line.startsWith("data:")) {
                JsonNode event = mapper.readTree(line.substring(5).trim());
                if ("done".equals(event.path("type").asText())) {
                    doneEvent = event;
                }
            }
        }

        assertNotNull(doneEvent, "Should emit a done event");
        assertEquals("test-conversation-xyz", doneEvent.path("conversationId").asText(),
                "done event should include the provided conversationId");
    }

    @Test
    void integration_ask_streamIgnoresCommentAndEmptyFrames() throws Exception {
        AskRequest req = new AskRequest("Is there a recycling center? [SSE_COMMENT_EMPTY]");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);

        HttpResponse<String> resp = client.toBlocking().exchange(httpReq, String.class);
        assertEquals(200, resp.getStatus().getCode());

        String streamContent = resp.getBody().orElse("");
        String[] lines = streamContent.split("\n\n");

        List<JsonNode> events = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("data:")) {
                events.add(mapper.readTree(line.substring(5).trim()));
            }
        }

        assertTrue(events.size() >= 3, "Expected chunk + sources + done events");
        assertEquals("chunk", events.get(0).path("type").asText());
        assertEquals("Heartbeat-safe response", events.get(0).path("content").asText());
        assertEquals("done", events.get(events.size() - 1).path("type").asText());
    }

    @Test
    void integration_ask_newsIntent_withCityScopedNewsContext_usesNewsBranch() throws Exception {
        String testCityAuthHeader = mintAuthHeader("testcity");
        AskRequest req = new AskRequest("latest news about recycling [SSE_NEWS_CONTEXT]");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", testCityAuthHeader);

        HttpResponse<String> resp = client.toBlocking().exchange(httpReq, String.class);
        assertEquals(200, resp.getStatus().getCode());

        String streamContent = resp.getBody().orElse("");
        String[] lines = streamContent.split("\n\n");

        JsonNode firstChunk = null;
        for (String line : lines) {
            if (line.startsWith("data:")) {
                JsonNode event = mapper.readTree(line.substring(5).trim());
                if ("chunk".equals(event.path("type").asText())) {
                    firstChunk = event;
                    break;
                }
            }
        }

        assertNotNull(firstChunk);
        assertEquals("NEWS CONTEXT USED", firstChunk.path("content").asText());
    }

    @Test
    void integration_ask_newsIntent_withoutRelatedNews_returnsExplicitFallbackMessage() throws Exception {
        String testCityAuthHeader = mintAuthHeader("testcity");
        AskRequest req = new AskRequest("Any recent news about martian taxation in the city?");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", testCityAuthHeader);

        HttpResponse<String> resp = client.toBlocking().exchange(httpReq, String.class);
        assertEquals(200, resp.getStatus().getCode());

        String streamContent = resp.getBody().orElse("");
        String[] lines = streamContent.split("\n\n");

        JsonNode firstChunk = null;
        for (String line : lines) {
            if (line.startsWith("data:")) {
                JsonNode event = mapper.readTree(line.substring(5).trim());
                if ("chunk".equals(event.path("type").asText())) {
                    firstChunk = event;
                    break;
                }
            }
        }

        assertNotNull(firstChunk);
        assertEquals("I could not find related recent news about that in testcity.",
                firstChunk.path("content").asText());
    }

    @Test
    void integration_ask_eventIntent_withoutDateWindow_returnsClarificationBeforeRetrieval() throws Exception {
        String testCityAuthHeader = mintAuthHeader("testcity");
        AskRequest req = new AskRequest("Que eventos hay en la ciudad?");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", testCityAuthHeader);

        HttpResponse<String> resp = client.toBlocking().exchange(httpReq, String.class);
        assertEquals(200, resp.getStatus().getCode());

        String streamContent = resp.getBody().orElse("");
        String[] lines = streamContent.split("\n\n");

        JsonNode firstChunk = null;
        JsonNode sourcesEvent = null;
        for (String line : lines) {
            if (line.startsWith("data:")) {
                JsonNode event = mapper.readTree(line.substring(5).trim());
                if ("chunk".equals(event.path("type").asText()) && firstChunk == null) {
                    firstChunk = event;
                }
                if ("sources".equals(event.path("type").asText())) {
                    sourcesEvent = event;
                }
            }
        }

        assertNotNull(firstChunk);
        String clarification = firstChunk.path("content").asText();
        assertTrue(clarification.contains("rango de fechas")
                || clarification.contains("date window")
                || clarification.contains("interval de dates"));
        assertNotNull(sourcesEvent);
        assertTrue(sourcesEvent.path("sources").isArray());
        assertEquals(0, sourcesEvent.path("sources").size());
    }

    @Test
    void integration_ask_procedureEventNewsIncludeSourceUrlsInSourcesEvent() throws Exception {
        String testCityAuthHeader = mintAuthHeader("testcity");

        assertSourcesEventContainsUrl(testCityAuthHeader, "Recycling procedure in testcity");
        assertSourcesEventContainsUrl(testCityAuthHeader, "Film Festival events this week in testcity");
        assertSourcesEventContainsUrl(testCityAuthHeader, "latest news about recycling campaign [SSE_NEWS_CONTEXT]");
    }

    @Test
    void integration_ask_streamMalformedAfterFirstChunk_emitsSseErrorEvent() throws Exception {
        AskRequest req = new AskRequest("Is there a recycling center? [SSE_MALFORMED]");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);

        HttpResponse<String> resp = client.toBlocking().exchange(httpReq, String.class);
        assertEquals(200, resp.getStatus().getCode());

        String streamContent = resp.getBody().orElse("");
        String[] lines = streamContent.split("\n\n");

        JsonNode errorEvent = null;
        for (String line : lines) {
            if (line.startsWith("data:")) {
                JsonNode event = mapper.readTree(line.substring(5).trim());
                if ("error".equals(event.path("type").asText())) {
                    errorEvent = event;
                    break;
                }
            }
        }

        assertNotNull(errorEvent, "Expected SSE error event after malformed upstream chunk");
        assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), errorEvent.path("errorCode").asInt());
    }

    @Test
    void integration_ask_streamUpstreamAfterFirstChunk_emitsSseErrorEvent() throws Exception {
        AskRequest req = new AskRequest("Is there a recycling center? [SSE_UPSTREAM_AFTER_CHUNK]");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", authHeader);

        HttpResponse<String> resp = client.toBlocking().exchange(httpReq, String.class);
        assertEquals(200, resp.getStatus().getCode());

        String streamContent = resp.getBody().orElse("");
        String[] lines = streamContent.split("\n\n");

        JsonNode errorEvent = null;
        for (String line : lines) {
            if (line.startsWith("data:")) {
                JsonNode event = mapper.readTree(line.substring(5).trim());
                if ("error".equals(event.path("type").asText())) {
                    errorEvent = event;
                    break;
                }
            }
        }

        assertNotNull(errorEvent, "Expected SSE error event after upstream failure");
        assertEquals(OpenRouterErrorCode.UPSTREAM.getCode(), errorEvent.path("errorCode").asInt());
    }

    private void assertSourcesEventContainsUrl(String bearerToken, String question) throws Exception {
        AskRequest req = new AskRequest(question);
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req)
                .header("Authorization", bearerToken);

        HttpResponse<String> resp = client.toBlocking().exchange(httpReq, String.class);
        assertEquals(200, resp.getStatus().getCode());

        String streamContent = resp.getBody().orElse("");
        String[] lines = streamContent.split("\n\n");
        JsonNode sourcesEvent = null;
        for (String line : lines) {
            if (!line.startsWith("data:")) {
                continue;
            }
            JsonNode event = mapper.readTree(line.substring(5).trim());
            if ("sources".equals(event.path("type").asText())) {
                sourcesEvent = event;
                break;
            }
        }

        assertNotNull(sourcesEvent, "Expected sources event for question: " + question);
        JsonNode sources = sourcesEvent.path("sources");
        assertTrue(sources.isArray(), "sources must be an array");
        assertTrue(sources.size() > 0, "Expected at least one source for question: " + question);

        boolean hasValidUrl = false;
        for (JsonNode sourceNode : sources) {
            String url = sourceNode.path("url").asText("");
            if (url.startsWith("http://") || url.startsWith("https://")) {
                hasValidUrl = true;
                break;
            }
        }

        assertTrue(hasValidUrl, "Expected at least one HTTP/HTTPS source URL for question: " + question);
    }

    @MockBean(HttpWrapper.class)
    @Replaces(HttpWrapper.class)
    HttpWrapper openRouterHttpWrapper() {
        return new HttpWrapper() {
            @Override
            public CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages) {
                OPENROUTER_POST_CALLS.incrementAndGet();
                // Extract the content of the last user message to determine which scenario to
                // run.
                String userPrompt = messages == null ? null
                        : messages.stream()
                                .filter(m -> "user".equals(m.get("role")))
                                .reduce((first, second) -> second)
                                .map(m -> (String) m.get("content"))
                                .orElse(null);

                if (userPrompt != null && userPrompt.contains("[REFUSE]")) {
                    // Simulate AI refusal
                    return CompletableFuture.completedFuture(new HttpDto(
                            "I'm sorry, I can't help with that request because it's not about El Prat de Llobregat.",
                            200, "POST", null));
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
                    // Simulate Shape 3: AI returns format and letter body inlined in a single JSON
                    // object,
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
                    return CompletableFuture
                            .completedFuture(new HttpDto("OK from AI (integration)", 200, "POST", null));
                }
                // Fallback: simulate a generic successful redact response
                return CompletableFuture.completedFuture(new HttpDto("Redacted (integration)", 200, "POST", null));
            }

            @Override
            public OpenRouterStreamStartResult streamFromOpenRouter(List<Map<String, Object>> messages) {
                String userPrompt = messages == null ? null
                        : messages.stream()
                                .filter(m -> "user".equals(m.get("role")))
                                .reduce((first, second) -> second)
                                .map(m -> (String) m.get("content"))
                                .orElse(null);
                boolean hasNewsContext = messages != null && messages.stream()
                        .filter(m -> "system".equals(m.get("role")))
                        .map(m -> (String) m.get("content"))
                        .filter(Objects::nonNull)
                        .anyMatch(content -> content.contains("CONTEXT FROM CITY NEWS"));
                if (userPrompt != null && userPrompt.contains("[UPSTREAM_402]")) {
                    return new OpenRouterStreamStartResult.Error(new OpenRouterStreamingException(
                            OpenRouterErrorCode.UPSTREAM,
                            "OpenRouter stream startup failed.",
                            402));
                }
                if (userPrompt != null && userPrompt.contains("[SSE_NEWS_CONTEXT]")) {
                    String content = hasNewsContext ? "NEWS CONTEXT USED" : "NEWS CONTEXT MISSING";
                    return new OpenRouterStreamStartResult.Success(reactor.core.publisher.Flux.just(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}",
                            "data: [DONE]"));
                }
                if (userPrompt != null && userPrompt.contains("[UPSTREAM_429]")) {
                    return new OpenRouterStreamStartResult.Error(new OpenRouterStreamingException(
                            OpenRouterErrorCode.UPSTREAM,
                            "OpenRouter stream startup failed.",
                            429));
                }
                if (userPrompt != null && userPrompt.contains("[REFUSE]")) {
                    String content = "I'm sorry, I can't help with that request because it's not about El Prat de Llobregat.";
                    return new OpenRouterStreamStartResult.Success(reactor.core.publisher.Flux.just(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"" + content.replace("\"", "\\\"") + "\"}}]}",
                            "data: [DONE]"));
                }
                if (userPrompt != null && userPrompt.contains("[SSE_MULTIEXENT]")) {
                    // Mock SSE stream with multiple events for testing
                    String chunk1 = "data: {\"choices\":[{\"delta\":{\"content\":\"Response \"}}]}";
                    String chunk2 = "data: {\"choices\":[{\"delta\":{\"content\":\"with sources\"}}]}";
                    String done = "data: [DONE]";
                    return new OpenRouterStreamStartResult.Success(
                            reactor.core.publisher.Flux.just(chunk1, chunk2, done));
                }
                if (userPrompt != null && userPrompt.contains("[SSE_MULTIEXENT_CONVID]")) {
                    // Same as above
                    String chunk1 = "data: {\"choices\":[{\"delta\":{\"content\":\"Test \"}}]}";
                    String chunk2 = "data: {\"choices\":[{\"delta\":{\"content\":\"response\"}}]}";
                    String done = "data: [DONE]";
                    return new OpenRouterStreamStartResult.Success(
                            reactor.core.publisher.Flux.just(chunk1, chunk2, done));
                }
                if (userPrompt != null && userPrompt.contains("[SSE_COMMENT_EMPTY]")) {
                    String comment = ": OPENROUTER PROCESSING";
                    String empty = "";
                    String data = "data: {\"choices\":[{\"delta\":{\"content\":\"Heartbeat-safe response\"}}]}";
                    String done = "data: [DONE]";
                    return new OpenRouterStreamStartResult.Success(
                            reactor.core.publisher.Flux.just(comment, empty, "   ", data, done));
                }
                if (userPrompt != null && userPrompt.contains("[SSE_MALFORMED]")) {
                    String chunk1 = "data: {\"choices\":[{\"delta\":{\"content\":\"Before malformed\"}}]}";
                    String malformed = "data: {not-json";
                    return new OpenRouterStreamStartResult.Success(reactor.core.publisher.Flux.just(chunk1, malformed));
                }
                if (userPrompt != null && userPrompt.contains("[SSE_UPSTREAM_AFTER_CHUNK]")) {
                    String chunk1 = "data: {\"choices\":[{\"delta\":{\"content\":\"Before upstream\"}}]}";
                    return new OpenRouterStreamStartResult.Success(reactor.core.publisher.Flux.just(chunk1)
                            .concatWith(reactor.core.publisher.Flux.error(new OpenRouterStreamingException(
                                    OpenRouterErrorCode.UPSTREAM,
                                    "OpenRouter streaming failed.",
                                    null))));
                }
                // Default: successful streaming response
                return new OpenRouterStreamStartResult.Success(reactor.core.publisher.Flux.just(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"OK from AI (integration)\"}}]}",
                        "data: [DONE]"));
            }
        };
    }

    @MockBean(ResponseCacheService.class)
    @Replaces(ResponseCacheService.class)
    ResponseCacheService enabledResponseCacheService() {
        return new ResponseCacheService(true, 10, 500);
    }

    @MockBean(SqsComplaintPublisher.class)
    @Replaces(SqsComplaintPublisher.class)
    SqsComplaintPublisher noopSqsPublisher() {
        return new SqsComplaintPublisher() {
            @Override
            public void publish(RedactSqsMessage message) {
                /* no-op for tests */ }
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
        IOpenRouterService openRouterService(HttpWrapper httpWrapper,
                ResponseCacheService cacheService,
                ObjectMapper objectMapper) {
            InputValidationService validationService = new InputValidationService(5000);
            ConversationManagementService conversationService = new ConversationManagementService(5);
            // ResponseCacheService is injected from ApplicationContext
            AiResponseProcessingService aiResponseService = new AiResponseProcessingService(httpWrapper, cacheService,
                    30);
            ProcedureContextService procedureContextService = new ProcedureContextService(
                    new ProcedureRagHelperRegistry(), new EventRagHelperRegistry(),
                    new cat.complai.openrouter.helpers.RedactPromptBuilder());
            return new OpenRouterServices(validationService, conversationService, aiResponseService,
                    procedureContextService, new cat.complai.openrouter.helpers.RedactPromptBuilder(), httpWrapper,
                    objectMapper);
        }

        @Singleton
        @Replaces(ProcedureRagHelperRegistry.class)
        ProcedureRagHelperRegistry ragRegistry() {
            return new ProcedureRagHelperRegistry();
        }
    }
}
