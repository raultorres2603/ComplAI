package cat.complai.services.stadistics.models;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

@DisplayName("FeedbackFile Tests")
class FeedbackFileTest {

    @Test
    @DisplayName("Should construct with fileName and URL")
    void shouldConstructWithFileNameAndUrl() throws MalformedURLException {
        URL url = URI.create("https://example.com/feedback.json").toURL();
        FeedbackFile file = new FeedbackFile("feedback.json", url);
        assertNotNull(file);
        assertEquals("feedback.json", file.fileName());
        assertEquals(url, file.url());
    }

    @Test
    @DisplayName("Should return correct fileName")
    void shouldReturnCorrectFileName() throws MalformedURLException {
        FeedbackFile file = new FeedbackFile("test-fb.json", URI.create("https://example.com/test-fb.json").toURL());
        assertEquals("test-fb.json", file.fileName());
    }

    @Test
    @DisplayName("Should return correct URL")
    void shouldReturnCorrectUrl() throws MalformedURLException {
        URL url = URI.create("https://example.com/test-fb.json").toURL();
        FeedbackFile file = new FeedbackFile("test-fb.json", url);
        assertEquals(url, file.url());
    }
}
