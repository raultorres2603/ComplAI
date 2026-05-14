package cat.complai.services.stadistics.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.MalformedURLException;
import java.net.URL;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FeedbackFile Tests")
class FeedbackFileTest {

    @Test
    @DisplayName("Should construct with fileName and URL")
    void shouldConstructWithFileNameAndUrl() throws MalformedURLException {
        URL url = new URL("https://example.com/feedback.json");
        FeedbackFile file = new FeedbackFile("feedback.json", url);
        assertNotNull(file);
        assertEquals("feedback.json", file.getFileName());
        assertEquals(url, file.getUrl());
    }

    @Test
    @DisplayName("Should return correct fileName")
    void shouldReturnCorrectFileName() throws MalformedURLException {
        FeedbackFile file = new FeedbackFile("test-fb.json", new URL("https://example.com/test-fb.json"));
        assertEquals("test-fb.json", file.getFileName());
    }

    @Test
    @DisplayName("Should return correct URL")
    void shouldReturnCorrectUrl() throws MalformedURLException {
        URL url = new URL("https://example.com/test-fb.json");
        FeedbackFile file = new FeedbackFile("test-fb.json", url);
        assertEquals(url, file.getUrl());
    }
}
