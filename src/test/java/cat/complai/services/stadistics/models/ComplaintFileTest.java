package cat.complai.services.stadistics.models;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.MalformedURLException;
import java.net.URL;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ComplaintFile Tests")
class ComplaintFileTest {

    @Test
    @DisplayName("Should construct with fileName and URL")
    void shouldConstructWithFileNameAndUrl() throws MalformedURLException {
        URL url = URI.create("https://example.com/complaint.pdf").toURL();
        ComplaintFile file = new ComplaintFile("complaint.pdf", url);
        assertNotNull(file);
        assertEquals("complaint.pdf", file.fileName());
        assertEquals(url, file.url());
    }

    @Test
    @DisplayName("Should return correct fileName")
    void shouldReturnCorrectFileName() throws MalformedURLException {
        ComplaintFile file = new ComplaintFile("test-file.pdf", URI.create("https://example.com/test-file.pdf").toURL());
        assertEquals("test-file.pdf", file.fileName());
    }

    @Test
    @DisplayName("Should return correct URL")
    void shouldReturnCorrectUrl() throws MalformedURLException {
        URL url = URI.create("https://example.com/test-file.pdf").toURL();
        ComplaintFile file = new ComplaintFile("test-file.pdf", url);
        assertEquals(url, file.url());
    }
}
