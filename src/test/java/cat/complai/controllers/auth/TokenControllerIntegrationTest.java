package cat.complai.controllers.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class TokenControllerIntegrationTest {

    @Inject
    @Client("/")
    HttpClient client;

    private static final String VALID_CLIENT_SECRET = "test-client-secret";
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        System.setProperty("ENABLE_CITY_ELPRAT", "true");
        System.setProperty("ENABLE_CITY_TESTCITY", "false");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("ENABLE_CITY_ELPRAT");
        System.clearProperty("ENABLE_CITY_TESTCITY");
    }

    private HttpResponse<String> postToken(String clientSecret, String cityId) {
        Map<String, String> body = Map.of(
                "clientSecret", clientSecret != null ? clientSecret : "",
                "cityId", cityId != null ? cityId : "");
        return client.toBlocking().exchange(
                HttpRequest.POST("/complai/auth/token", body), String.class);
    }

    /**
     * Posts to token endpoint and expects a non-2xx response.
     * Returns the HttpClientResponseException so tests can inspect status and body.
     */
    private HttpClientResponseException postTokenExpectError(String clientSecret, String cityId) {
        Map<String, String> body = Map.of(
                "clientSecret", clientSecret != null ? clientSecret : "",
                "cityId", cityId != null ? cityId : "");
        try {
            client.toBlocking().exchange(
                    HttpRequest.POST("/complai/auth/token", body), String.class);
            fail("Expected HttpClientResponseException");
            return null; // unreachable
        } catch (HttpClientResponseException e) {
            return e;
        }
    }

    @Nested
    @DisplayName("POST /complai/auth/token")
    class TokenEndpoint {

        @Test
        @DisplayName("valid secret + enabled city → 200 + JWT")
        void token_validSecretEnabledCity_returns200() throws Exception {
            HttpResponse<String> response = postToken(VALID_CLIENT_SECRET, "elprat");
            assertEquals(HttpStatus.OK, response.getStatus());

            JsonNode body = mapper.readTree(response.body());
            assertTrue(body.has("token"));
            assertTrue(body.has("expiresIn"));
            assertEquals("elprat", body.get("cityId").asText());
            // Verify the token looks like a JWT (three dot-separated parts)
            String token = body.get("token").asText();
            assertEquals(3, token.split("\\.").length, "Token should be a valid JWT format");
        }

        @Test
        @DisplayName("invalid secret → 401")
        void token_invalidSecret_returns401() {
            HttpClientResponseException ex = postTokenExpectError("wrong-secret", "elprat");
            assertEquals(401, ex.getStatus().getCode());
        }

        @Test
        @DisplayName("missing secret → 400")
        void token_missingSecret_returns400() {
            HttpClientResponseException ex = postTokenExpectError("", "elprat");
            assertEquals(400, ex.getStatus().getCode());
        }

        @Test
        @DisplayName("missing cityId → 400")
        void token_missingCityId_returns400() {
            HttpClientResponseException ex = postTokenExpectError(VALID_CLIENT_SECRET, "");
            assertEquals(400, ex.getStatus().getCode());
        }

        @Test
        @DisplayName("valid secret + disabled city → 503")
        void token_disabledCity_returns503() {
            HttpClientResponseException ex = postTokenExpectError(VALID_CLIENT_SECRET, "testcity");
            assertEquals(503, ex.getStatus().getCode());
        }
    }
}
