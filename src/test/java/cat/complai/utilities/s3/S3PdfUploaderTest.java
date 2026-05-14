package cat.complai.utilities.s3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.lang.reflect.Field;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class S3PdfUploaderTest {

    @Mock
    S3Client s3Client;

    @Mock
    S3Presigner s3Presigner;

    @Mock
    PresignedGetObjectRequest presignedRequest;

    /** Reflectively sets the private field on the no-arg instance. */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = S3PdfUploader.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private S3PdfUploader newUploader(String bucketName) throws Exception {
        var u = new S3PdfUploader() {}; // no-arg ctor via anonymous subclass
        setField(u, "s3Client", s3Client);
        setField(u, "s3Presigner", s3Presigner);
        setField(u, "bucketName", bucketName);
        return u;
    }

    @Test
    void noArgConstructor_doesNotThrow() {
        assertDoesNotThrow(() -> new S3PdfUploader() {});
    }

    @Test
    void close_withNullFields_doesNotThrow() {
        var uploader = new S3PdfUploader() {};
        assertDoesNotThrow(uploader::close);
    }

    @Test
    void upload_callsPutObjectWithCorrectParams() throws Exception {
        var uploader = newUploader("test-bucket");
        byte[] pdfBytes = "fake-pdf-content".getBytes();

        uploader.upload("complaints/test-123.pdf", pdfBytes);

        var captor = ArgumentCaptor.<PutObjectRequest>captor();
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        var request = captor.getValue();
        assertEquals("test-bucket", request.bucket());
        assertEquals("complaints/test-123.pdf", request.key());
        assertEquals("application/pdf", request.contentType());
        assertEquals((long) pdfBytes.length, request.contentLength());
        assertEquals("inline; filename=\"complaint.pdf\"", request.contentDisposition());
    }

    @Test
    void upload_whenS3Throws_rethrowsAsRuntimeException() throws Exception {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(new RuntimeException("Simulated S3 failure"));

        var uploader = newUploader("test-bucket");

        var ex = assertThrows(RuntimeException.class,
                () -> uploader.upload("fail-key.pdf", "data".getBytes()));
        assertTrue(ex.getMessage().contains("fail-key.pdf"));
    }

    @Test
    void generatePresignedGetUrl_returnsPresignedUrl(@Mock URL mockUrl) throws Exception {
        when(mockUrl.toString()).thenReturn("https://presigned.example.com/test.pdf");
        when(presignedRequest.url()).thenReturn(mockUrl);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenReturn(presignedRequest);

        var uploader = newUploader("test-bucket");

        String url = uploader.generatePresignedGetUrl("complaints/test-123.pdf");

        assertEquals("https://presigned.example.com/test.pdf", url);
        verify(s3Presigner).presignGetObject(any(GetObjectPresignRequest.class));
    }

    @Test
    void close_closesBothClients() throws Exception {
        var uploader = newUploader("bucket");

        uploader.close();

        verify(s3Client).close();
        verify(s3Presigner).close();
    }

    @Test
    void close_whenS3ClientCloseThrows_stillClosesPresigner() throws Exception {
        doThrow(new RuntimeException("S3Client close failed")).when(s3Client).close();

        var uploader = newUploader("bucket");

        assertDoesNotThrow(uploader::close);
        verify(s3Client).close();
        verify(s3Presigner).close();
    }

    @Test
    void close_whenBothCloseThrow_doesNotPropagate() throws Exception {
        doThrow(new RuntimeException("S3Client error")).when(s3Client).close();
        doThrow(new RuntimeException("S3Presigner error")).when(s3Presigner).close();

        var uploader = newUploader("bucket");

        assertDoesNotThrow(uploader::close);
        verify(s3Client).close();
        verify(s3Presigner).close();
    }
}
