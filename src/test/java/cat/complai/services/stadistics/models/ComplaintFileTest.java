package cat.complai.services.stadistics.models;

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
        URL url = new URL("https://example.com/complaint.pdf");
        ComplaintFile file = new ComplaintFile("complaint.pdf", url);
        assertNotNull(file);
        assertEquals("complaint.pdf", file.getFileName());
        assertEquals(url, file.getUrl());
    }

    @Test
    @DisplayName("Should return correct fileName")
    void shouldReturnCorrectFileName() throws MalformedURLException {
        ComplaintFile file = new ComplaintFile("test-file.pdf", new URL("https://example.com/test-file.pdf"));
        assertEquals("test-file.pdf", file.getFileName());
    }

    @Test
    @DisplayName("Should return correct URL")
    void shouldReturnCorrectUrl() throws MalformedURLException {
        URL url = new URL("https://example.com/test-file.pdf");
        ComplaintFile file = new ComplaintFile("test-file.pdf", url);
        assertEquals(url, file.getUrl());
    }
}
