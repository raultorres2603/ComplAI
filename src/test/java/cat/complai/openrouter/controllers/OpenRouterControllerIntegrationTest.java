package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OutputFormat;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.controllers.dto.AskRequest;
import cat.complai.openrouter.controllers.dto.RedactRequest;
import cat.complai.openrouter.interfaces.services.OpenRouterServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.Test;

import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;

import java.util.List;
import java.util.Map;
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
        OpenRouterResponseDto dto = openRouterService.redactComplaint("Complaint about noise [HEADER]", OutputFormat.AUTO, null);
        assertTrue(dto.isSuccess(), "Expected success");
        assertNotNull(dto.getPdfData(), "Expected PDF data");
        assertTrue(dto.getPdfData().length > 0);
        assertTrue(new String(dto.getPdfData(), 0, 4).startsWith("%PDF"), "Expected PDF magic bytes");
    }

    @Test
    void integration_redact_missingHeader_fallsBackToJsonSuccess() {
        // AI returns plain text without JSON header. The service must NOT reject with 400.
        // Because the client did not explicitly request PDF (format=AUTO), the service degrades
        // gracefully and returns the raw AI message as a 200 JSON response.
        RedactRequest req = new RedactRequest("Complaint with no header [NOHEADER]");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        Optional<OpenRouterPublicDto> bodyOpt = resp.getBody();
        assertTrue(bodyOpt.isPresent());
        assertTrue(bodyOpt.get().isSuccess());
        assertNotNull(bodyOpt.get().getMessage());
    }

    @Test
    void integration_redact_headerWithInlineJsonBody_producesPdf() {
        OpenRouterResponseDto dto = openRouterService.redactComplaint("Complaint that requests inline body [HEADER]", OutputFormat.AUTO, null);
        assertTrue(dto.isSuccess(), "Expected success");
        assertNotNull(dto.getPdfData(), "Expected PDF data");
        assertTrue(dto.getPdfData().length > 0);
        assertTrue(new String(dto.getPdfData(), 0, 4).startsWith("%PDF"), "Expected PDF magic bytes");
    }

    @Test
    void integration_redact_longProducesMultiplePages() {
        OpenRouterResponseDto dto = openRouterService.redactComplaint("Very long complaint [HEADER_LONG]", OutputFormat.AUTO, null);
        assertTrue(dto.isSuccess(), "Expected success");
        assertNotNull(dto.getPdfData(), "Expected PDF data");
        assertTrue(dto.getPdfData().length > 0);
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(dto.getPdfData());
             org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.pdmodel.PDDocument.load(in)) {
            assertTrue(doc.getNumberOfPages() > 1, "Expected multi-page PDF");
        } catch (Exception e) {
            fail("PDF parsing failed: " + e.getMessage());
        }
    }

    @Test
    void integration_redact_invalidHeaderFormat_fallsBackToJsonSuccess() {
        // AI returns a JSON header with an unrecognised format ("xml"). OutputFormat.fromString
        // treats unknown values as AUTO, which triggers the graceful fallback: the service returns
        // 200 with the raw AI message instead of failing with a 400.
        RedactRequest req = new RedactRequest("Complaint with invalid header [HEADER_INVALID]");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        Optional<OpenRouterPublicDto> bodyOpt = resp.getBody();
        assertTrue(bodyOpt.isPresent());
        assertTrue(bodyOpt.get().isSuccess());
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
                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"format\": \"pdf\"}\n\nDear Ajuntament,\n\n");
                    sb.append("This is a long complaint sentence to generate many pages. ".repeat(800));
                    sb.append("\n\nSincerely,\nResident");
                    return CompletableFuture.completedFuture(new HttpDto(sb.toString(), 200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[HEADER_INVALID]")) {
                    // Simulate invalid header
                    String body = "{\"format\": \"xml\"}\n\nThis body should be rejected due to invalid format.";
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

    @Factory
    static class TestBeans {
        @Singleton
        @Replaces(OpenRouterServices.class)
        IOpenRouterService openRouterService(HttpWrapper httpWrapper) {
            return new OpenRouterServices(httpWrapper);
        }
    }
}
