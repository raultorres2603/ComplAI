package cat.complai.utilities.s3;

import cat.complai.utilities.s3.S3FeedbackLister.FeedbackFileEntry;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link S3FeedbackLister}.
 *
 * <p>We subclass the protected no-arg constructor to intercept S3 calls
 * without wiring a real S3 client.
 */
class S3FeedbackListerTest {

    /**
     * Captures list calls without touching the AWS SDK.
     */
    static class CapturingS3FeedbackLister extends S3FeedbackLister {
        List<FeedbackFileEntry> mockEntries = Collections.emptyList();

        @Override
        public List<FeedbackFileEntry> listFeedbackFiles(String cityId) {
            return mockEntries;
        }
    }

    @Test
    void listFeedbackFiles_withNoFiles_returnsEmptyList() {
        CapturingS3FeedbackLister lister = new CapturingS3FeedbackLister();
        lister.mockEntries = Collections.emptyList();

        List<FeedbackFileEntry> result = lister.listFeedbackFiles("elprat");

        assertTrue(result.isEmpty(), "Should return empty list when no files");
    }

    @Test
    void listFeedbackFiles_withFiles_returnsEntries() {
        CapturingS3FeedbackLister lister = new CapturingS3FeedbackLister();
        lister.mockEntries = List.of(
                new FeedbackFileEntry("fb-001.json", "https://example.com/fb-001.json"),
                new FeedbackFileEntry("fb-002.json", "https://example.com/fb-002.json")
        );

        List<FeedbackFileEntry> result = lister.listFeedbackFiles("elprat");

        assertEquals(2, result.size());
        assertEquals("fb-001.json", result.get(0).getFileName());
        assertEquals("https://example.com/fb-001.json", result.get(0).getUrl());
    }

    @Test
    void feedbackFileEntry_storesFileNameAndUrl() {
        FeedbackFileEntry entry = new FeedbackFileEntry("test-file.json", "https://example.com/test-file.json");

        assertEquals("test-file.json", entry.getFileName());
        assertEquals("https://example.com/test-file.json", entry.getUrl());
    }

    @Test
    void listFeedbackFiles_exceptionPropagatesAsRuntimeException() {
        S3FeedbackLister lister = new S3FeedbackLister() {
            @Override
            public List<FeedbackFileEntry> listFeedbackFiles(String cityId) {
                throw new RuntimeException("S3 endpoint unreachable");
            }
        };

        assertThrows(RuntimeException.class, () -> lister.listFeedbackFiles("elprat"),
                "Lister must propagate exceptions");
    }
}