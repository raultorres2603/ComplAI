package cat.complai.controllers.privacy;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP integration test verifying that the privacy policy endpoint is excluded
 * from API key authentication.
 *
 * <p>{@code GET /privacy} must return HTTP 200 with text/html content <em>without</em>
 * an {@code X-Api-Key} header. This test runs through the full Micronaut filter chain
 * (including {@link cat.complai.utilities.auth.TestApiKeyFilter}) to confirm the
 * exclusion works end-to-end.
 *
 * <p>Regression guard: previously {@code TestApiKeyFilter} was missing the
 * {@code /privacy} exclusion, so tests could not catch production failures where
 * the endpoint returned 401 "Missing X-Api-Key header".
 */
@MicronautTest
class PrivacyEndpointAuthTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void getPrivacy_withoutApiKey_returns200() {
        HttpResponse<String> response = client.toBlocking()
                .exchange(HttpRequest.GET("/privacy?lang=ca"), String.class);

        assertEquals(200, response.getStatus().getCode());
    }

    @Test
    void getPrivacy_withoutApiKey_returnsHtml() {
        HttpResponse<String> response = client.toBlocking()
                .exchange(HttpRequest.GET("/privacy?lang=en"), String.class);

        assertEquals(200, response.getStatus().getCode());
        assertTrue(response.getContentType().isPresent());
        assertEquals(MediaType.TEXT_HTML_TYPE, response.getContentType().get());
    }

    @Test
    void getPrivacy_withoutApiKey_containsCatalanContent() {
        HttpResponse<String> response = client.toBlocking()
                .exchange(HttpRequest.GET("/privacy?lang=ca"), String.class);

        assertEquals(200, response.getStatus().getCode());
        String body = response.body();
        assertNotNull(body);
        assertTrue(body.contains("Política de Privacitat"),
                "Response should contain Catalan privacy policy heading");
    }

    @Test
    void getPrivacy_withoutApiKey_containsEnglishContent() {
        HttpResponse<String> response = client.toBlocking()
                .exchange(HttpRequest.GET("/privacy?lang=en"), String.class);

        assertEquals(200, response.getStatus().getCode());
        String body = response.body();
        assertNotNull(body);
        assertTrue(body.contains("Privacy Policy"),
                "Response should contain English privacy policy heading");
    }

    @Test
    void getPrivacy_withoutApiKey_containsSpanishContent() {
        HttpResponse<String> response = client.toBlocking()
                .exchange(HttpRequest.GET("/privacy?lang=es"), String.class);

        assertEquals(200, response.getStatus().getCode());
        String body = response.body();
        assertNotNull(body);
        assertTrue(body.contains("Política de Privacidad"),
                "Response should contain Spanish privacy policy heading");
    }
}
