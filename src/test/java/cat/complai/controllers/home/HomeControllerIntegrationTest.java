package cat.complai.controllers.home;

import cat.complai.dto.home.HomeDto;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class HomeControllerIntegrationTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void rootEndpoint_returnsWelcomeMessage() {
        HttpResponse<HomeDto> response = client.toBlocking().exchange("/", HomeDto.class);

        assertEquals(200, response.getStatus().getCode());
        assertTrue(response.getBody().isPresent());
        assertEquals("Welcome to the Complai Home Page!", response.getBody().get().getMessage());
    }
}
