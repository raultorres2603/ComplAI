package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OutputFormat;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.controllers.dto.AskRequest;
import cat.complai.openrouter.controllers.dto.RedactRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.annotation.MockBean;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@MicronautTest
public class OpenRouterControllerIntegrationTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    HttpWrapper httpWrapper; // injected mock

    // Injected to test PDF generation directly, bypassing the HTTP layer.
    // Micronaut's embedded Netty server closes the connection for binary responses,
    // so PDF correctness must be verified at the service level, not via HTTP.
    @Inject
    IOpenRouterService openRouterService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void integration_ask_success() {
        AskRequest req = new AskRequest("Is there a recycling center?");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        Optional<OpenRouterPublicDto> bodyOpt = resp.getBody();
        assertTrue(bodyOpt.isPresent());
        OpenRouterPublicDto body = bodyOpt.get();
        assertTrue(body.isSuccess());
        assertEquals("OK from AI (integration)", body.getMessage());
    }

    @Test
    void integration_redact_success() {
        RedactRequest req = new RedactRequest("There is noise from the airport");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req);
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
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req);
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
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req);
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
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req);
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
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req);
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
    void integration_redact_aiHeader_producesPdf() {
        // Verify the service produces valid PDF bytes when the mock returns a JSON header with format=pdf.
        // Tested at service level: Micronaut's embedded Netty server closes the connection for binary
        // HTTP responses, making byte[] retrieval through the test HTTP client unreliable.
        OpenRouterResponseDto dto = openRouterService.redactComplaint("Complaint about noise [HEADER]", OutputFormat.AUTO);
        assertTrue(dto.isSuccess(), "Expected success");
        assertNotNull(dto.getPdfData(), "Expected PDF data");
        assertTrue(dto.getPdfData().length > 0);
        assertTrue(new String(dto.getPdfData(), 0, 4).startsWith("%PDF"), "Expected PDF magic bytes");
    }

    @Test
    void integration_redact_missingHeader_rejected() throws Exception {
        // AI returns plain text without JSON header -> service rejects with 400
        RedactRequest req = new RedactRequest("Complaint with no header [NOHEADER]");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req);
        try {
            client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
            fail("Expected HttpClientResponseException for 400");
        } catch (HttpClientResponseException e) {
            assertEquals(400, e.getStatus().getCode());
            String bodyJson = e.getResponse().getBody(String.class).orElse("{}");
            JsonNode node = mapper.readTree(bodyJson);
            assertNotNull(node);
            // validation error code mapped
            assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), node.path("errorCode").asInt());
        }
    }

    @Test
    void integration_redact_headerWithInlineJsonBody_producesPdf() {
        OpenRouterResponseDto dto = openRouterService.redactComplaint("Complaint that requests inline body [HEADER]", OutputFormat.AUTO);
        assertTrue(dto.isSuccess(), "Expected success");
        assertNotNull(dto.getPdfData(), "Expected PDF data");
        assertTrue(dto.getPdfData().length > 0);
        assertTrue(new String(dto.getPdfData(), 0, 4).startsWith("%PDF"), "Expected PDF magic bytes");
    }

    @Test
    void integration_redact_longProducesMultiplePages() throws Exception {
        OpenRouterResponseDto dto = openRouterService.redactComplaint("Very long complaint [HEADER_LONG]", OutputFormat.AUTO);
        assertTrue(dto.isSuccess(), "Expected success");
        assertNotNull(dto.getPdfData(), "Expected PDF data");
        assertTrue(dto.getPdfData().length > 0);
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(dto.getPdfData());
             org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.pdmodel.PDDocument.load(in)) {
            assertTrue(doc.getNumberOfPages() > 1, "Expected multi-page PDF");
        }
    }

    @Test
    void integration_redact_invalidHeader_rejected() throws Exception {
        // AI returns a JSON header with invalid format -> service should reject
        RedactRequest req = new RedactRequest("Complaint with invalid header [HEADER_INVALID]");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req);
        try {
            client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
            fail("Expected HttpClientResponseException for 400 due to invalid header");
        } catch (HttpClientResponseException e) {
            assertEquals(400, e.getStatus().getCode());
            String bodyJson = e.getResponse().getBody(String.class).orElse("{}");
            JsonNode node = mapper.readTree(bodyJson);
            assertNotNull(node);
            assertEquals(OpenRouterErrorCode.VALIDATION.getCode(), node.path("errorCode").asInt());
        }
    }

    @MockBean(HttpWrapper.class)
    HttpWrapper openRouterHttpWrapper() {
        return new HttpWrapper() {
            @Override
            public CompletableFuture<HttpDto> postToOpenRouterAsync(String userPrompt) {
                // Simulate AI responses based on embedded markers in the prompt
                if (userPrompt != null && userPrompt.contains("[REFUSE]")) {
                    return CompletableFuture.completedFuture(new HttpDto("I'm sorry, I can't help with that request because it's not about El Prat de Llobregat.", 200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[UPSTREAM]")) {
                    return CompletableFuture.completedFuture(new HttpDto(null, 500, "POST", "Missing OPENROUTER_API_KEY"));
                }
                if (userPrompt != null && userPrompt.contains("[HEADER]")) {
                    // Return a JSON header followed by the letter body so the real service parses and generates a PDF
                    String body = "{\"format\": \"pdf\"}\n\nDear Ajuntament,\n\nI am writing to complain about noise...\n\nSincerely,\nResident";
                    return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[NOHEADER]")) {
                    // Return plain text with no JSON header
                    String body = "I will not emit a JSON header.";
                    return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[HEADER_LONG]")) {
                    // Return a JSON header followed by a very long body to exercise pagination
                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"format\": \"pdf\"}\n\n");
                    sb.append("Dear Ajuntament,\n\n");
                    sb.append("This is a long complaint sentence to generate many pages. ".repeat(800));
                    sb.append("\n\nSincerely,\nResident");
                    return CompletableFuture.completedFuture(new HttpDto(sb.toString(), 200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[HEADER_INVALID]")) {
                    // Return a JSON header with unsupported format
                    String body = "{\"format\": \"xml\"}\n\nThis body should be rejected due to invalid format.";
                    return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
                }
                // For redact prompts without markers, the prompt includes the literal 'Complaint text:'; return a JSON header + cleaned body
                if (userPrompt != null && userPrompt.contains("Complaint text:")) {
                    String body = "{\"format\": \"json\"}\n\nRedacted (integration)";
                    return CompletableFuture.completedFuture(new HttpDto(body, 200, "POST", null));
                }
                // Default successful text response
                return CompletableFuture.completedFuture(new HttpDto("OK from AI (integration)", 200, "POST", null));
            }
        };
    }
}
