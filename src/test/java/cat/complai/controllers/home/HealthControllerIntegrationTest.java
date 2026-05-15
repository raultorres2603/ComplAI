package cat.complai.controllers.home;

import cat.complai.dto.home.HealthDto;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class HealthControllerIntegrationTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void healthEndpoint_returnsUp_withAllDependencyChecks() {
        HttpResponse<HealthDto> response = client.toBlocking().exchange("/health", HealthDto.class);

        assertEquals(200, response.getStatus().getCode());
        assertTrue(response.getBody().isPresent());
        HealthDto dto = response.getBody().get();
        assertEquals("UP", dto.getStatus());

        // Verify that the response includes all expected dependency checks
        Map<String, Object> checks = dto.getChecks();
        assertNotNull(checks, "Health checks map must not be null");
        assertTrue(checks.containsKey("s3"), "Response must include S3 check");
        assertTrue(checks.containsKey("sqs"), "Response must include SQS check");
        assertTrue(checks.containsKey("ses"), "Response must include SES check");
        assertTrue(checks.containsKey("ragIndexes"), "Response must include RAG indexes check");
        assertTrue(checks.containsKey("openRouterApiKeyConfigured"),
                "Response must include OpenRouter API key check");

        // Verify the mocked response values match what we configured in the mock bean
        @SuppressWarnings("unchecked")
        Map<String, Object> s3Result = (Map<String, Object>) checks.get("s3");
        assertEquals(false, s3Result.get("status"));
        assertEquals("S3 check (mocked)", s3Result.get("message"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sesResult = (Map<String, Object>) checks.get("ses");
        assertEquals(true, sesResult.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> ragResult = (Map<String, Object>) checks.get("ragIndexes");
        assertEquals(true, ragResult.get("status"));
        assertEquals(10, ragResult.get("items"));
    }

    @Test
    void healthStartupEndpoint_returnsUp() {
        HttpResponse<HealthDto> response = client.toBlocking().exchange("/health/startup", HealthDto.class);

        assertEquals(200, response.getStatus().getCode());
        assertTrue(response.getBody().isPresent());
        assertEquals("UP", response.getBody().get().getStatus());
    }

}
