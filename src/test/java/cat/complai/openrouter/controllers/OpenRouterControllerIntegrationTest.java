package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.controllers.dto.AskRequest;
import cat.complai.openrouter.controllers.dto.RedactRequest;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.annotation.MockBean;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
public class OpenRouterControllerIntegrationTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void integration_ask_success() {
        AskRequest req = new AskRequest("Is there a recycling center?");
        HttpRequest<AskRequest> httpReq = HttpRequest.POST("/openrouter/ask", req);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        assertTrue(resp.getBody().get().isSuccess());
        assertEquals("OK from AI (integration)", resp.getBody().get().getMessage());
    }

    @Test
    void integration_redact_success() {
        RedactRequest req = new RedactRequest("There is noise from the airport");
        HttpRequest<RedactRequest> httpReq = HttpRequest.POST("/openrouter/redact", req);
        HttpResponse<OpenRouterPublicDto> resp = client.toBlocking().exchange(httpReq, OpenRouterPublicDto.class);
        assertEquals(200, resp.getStatus().getCode());
        assertTrue(resp.getBody().get().isSuccess());
        assertEquals("Redacted (integration)", resp.getBody().get().getMessage());
    }

    @MockBean(IOpenRouterService.class)
    IOpenRouterService openRouterService() {
        return new IOpenRouterService() {
            @Override
            public OpenRouterResponseDto ask(String question) {
                return new OpenRouterResponseDto(true, "OK from AI (integration)", null, 200, OpenRouterErrorCode.NONE);
            }

            @Override
            public OpenRouterResponseDto redactComplaint(String complaint) {
                return new OpenRouterResponseDto(true, "Redacted (integration)", null, 200, OpenRouterErrorCode.NONE);
            }
        };
    }
}
