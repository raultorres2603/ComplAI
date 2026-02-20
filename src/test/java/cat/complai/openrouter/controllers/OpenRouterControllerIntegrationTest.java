package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
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

import cat.complai.openrouter.dto.OutputFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@MicronautTest
public class OpenRouterControllerIntegrationTest {

    @Inject
    @Client("/")
    HttpClient client;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void integration_ask_success() {
        AskRequest req = new AskRequest("Is there a recycling center?");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/complai/ask", req);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        assertTrue(resp.getBody().get().isSuccess());
        assertEquals("OK from AI (integration)", resp.getBody().get().getMessage());
    }

    @Test
    void integration_redact_success() {
        RedactRequest req = new RedactRequest("There is noise from the airport");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/complai/redact", req);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        assertTrue(resp.getBody().get().isSuccess());
        assertEquals("Redacted (integration)", resp.getBody().get().getMessage());
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

    @MockBean(IOpenRouterService.class)
    IOpenRouterService openRouterService() {
        return new IOpenRouterService() {
            @Override
            public OpenRouterResponseDto ask(String question) {
                if (question != null && question.contains("[REFUSE]")) {
                    return new OpenRouterResponseDto(false, null, "Request is not about El Prat de Llobregat.", 200, OpenRouterErrorCode.REFUSAL);
                }
                if (question != null && question.contains("[UPSTREAM]")) {
                    return new OpenRouterResponseDto(false, null, "Missing OPENROUTER_API_KEY", 500, OpenRouterErrorCode.UPSTREAM);
                }
                return new OpenRouterResponseDto(true, "OK from AI (integration)", null, 200, OpenRouterErrorCode.NONE);
            }

            @Override
            public OpenRouterResponseDto redactComplaint(String complaint, OutputFormat format) {
                if (complaint != null && complaint.contains("[REFUSE]")) {
                    return new OpenRouterResponseDto(false, null, "Request is not about El Prat de Llobregat.", 200, OpenRouterErrorCode.REFUSAL);
                }
                if (complaint != null && complaint.contains("[UPSTREAM]")) {
                    return new OpenRouterResponseDto(false, null, "OpenRouter non-2xx response: 500", 500, OpenRouterErrorCode.UPSTREAM);
                }
                return new OpenRouterResponseDto(true, "Redacted (integration)", null, 200, OpenRouterErrorCode.NONE);
            }
        };
    }
}
