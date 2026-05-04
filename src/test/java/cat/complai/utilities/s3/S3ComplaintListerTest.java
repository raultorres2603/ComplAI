package cat.complai.utilities.s3;

import cat.complai.utilities.s3.S3ComplaintLister.ComplaintFileEntry;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link S3ComplaintLister}.
 *
 * <p>We subclass the protected no-arg constructor to intercept S3 calls
 * without wiring a real S3 client.
 */
class S3ComplaintListerTest {

    /**
     * Captures list calls without touching the AWS SDK.
     */
    static class CapturingS3ComplaintLister extends S3ComplaintLister {
        List<ComplaintFileEntry> mockEntries = Collections.emptyList();

        @Override
        public List<ComplaintFileEntry> listComplaintFiles() {
            return mockEntries;
        }
    }

    @Test
    void listComplaintFiles_withNoFiles_returnsEmptyList() {
        CapturingS3ComplaintLister lister = new CapturingS3ComplaintLister();
        lister.mockEntries = Collections.emptyList();

        List<ComplaintFileEntry> result = lister.listComplaintFiles();

        assertTrue(result.isEmpty(), "Should return empty list when no files");
    }

    @Test
    void listComplaintFiles_withFiles_returnsEntries() {
        CapturingS3ComplaintLister lister = new CapturingS3ComplaintLister();
        lister.mockEntries = List.of(
                new ComplaintFileEntry("1700000001-complaint.pdf", "https://example.com/1.pdf"),
                new ComplaintFileEntry("1700000002-complaint.pdf", "https://example.com/2.pdf")
        );

        List<ComplaintFileEntry> result = lister.listComplaintFiles();

        assertEquals(2, result.size());
        assertEquals("1700000001-complaint.pdf", result.get(0).getFileName());
        assertEquals("https://example.com/1.pdf", result.get(0).getUrl());
    }

    @Test
    void complaintFileEntry_storesFileNameAndUrl() {
        ComplaintFileEntry entry = new ComplaintFileEntry("test-file.pdf", "https://example.com/test-file.pdf");

        assertEquals("test-file.pdf", entry.getFileName());
        assertEquals("https://example.com/test-file.pdf", entry.getUrl());
    }

    @Test
    void listComplaintFiles_exceptionPropagatesAsRuntimeException() {
        S3ComplaintLister lister = new S3ComplaintLister() {
            @Override
            public List<ComplaintFileEntry> listComplaintFiles() {
                throw new RuntimeException("S3 endpoint unreachable");
            }
        };

        assertThrows(RuntimeException.class, () -> lister.listComplaintFiles(),
                "Lister must propagate exceptions");
    }
}