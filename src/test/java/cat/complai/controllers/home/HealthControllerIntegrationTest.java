package cat.complai.controllers.home;

import cat.complai.dto.home.HealthDto;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class HealthControllerIntegrationTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void healthEndpoint_returnsUp() {
        HttpResponse<HealthDto> response = client.toBlocking().exchange("/health", HealthDto.class);

        assertEquals(200, response.getStatus().getCode());
        assertTrue(response.getBody().isPresent());
        assertEquals("UP", response.getBody().get().getStatus());
    }

    @Test
    void healthStartupEndpoint_returnsUp() {
        HttpResponse<HealthDto> response = client.toBlocking().exchange("/health/startup", HealthDto.class);

        assertEquals(200, response.getStatus().getCode());
        assertTrue(response.getBody().isPresent());
        assertEquals("UP", response.getBody().get().getStatus());
    }
}
