package cat.complai.utilities.scraper;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProcedureIndexLoaderTest {

    @Test
    void constructor_setsFields() {
        ProcedureIndexLoader loader = new ProcedureIndexLoader(
                "complai-procedures-development", "procedures-gencat.json", Region.EU_WEST_1);
        assertNotNull(loader);
    }

    @Test
    void downloadProceduresJson_success() throws Exception {
        String sampleJson = "{\"procedures\":[{\"id\":\"1\",\"title\":\"Test\"}]}";
        InputStream fakeStream = new ByteArrayInputStream(sampleJson.getBytes(StandardCharsets.UTF_8));
        GetObjectResponse response = GetObjectResponse.builder().build();
        ResponseInputStream<GetObjectResponse> responseStream =
                new ResponseInputStream<>(response, fakeStream);

        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

        try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class)) {
            S3ClientBuilder mockBuilder = mock(S3ClientBuilder.class);
            when(mockBuilder.region(any(Region.class))).thenReturn(mockBuilder);
            when(mockBuilder.credentialsProvider(any(AwsCredentialsProvider.class))).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockS3);
            s3Static.when(S3Client::builder).thenReturn(mockBuilder);

            ProcedureIndexLoader loader = new ProcedureIndexLoader(
                    "bucket", "key", Region.EU_WEST_1);
            Path result = loader.downloadProceduresJson();

            assertTrue(Files.exists(result));
            String content = Files.readString(result);
            assertEquals(sampleJson, content);

            Files.deleteIfExists(result);
        }
    }

    @Test
    void downloadProceduresJson_s3Error_throws() {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.getObject(any(GetObjectRequest.class)))
                .thenThrow(new RuntimeException("S3 error"));

        try (MockedStatic<S3Client> s3Static = mockStatic(S3Client.class)) {
            S3ClientBuilder mockBuilder = mock(S3ClientBuilder.class);
            when(mockBuilder.region(any(Region.class))).thenReturn(mockBuilder);
            when(mockBuilder.credentialsProvider(any(AwsCredentialsProvider.class))).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockS3);
            s3Static.when(S3Client::builder).thenReturn(mockBuilder);

            ProcedureIndexLoader loader = new ProcedureIndexLoader(
                    "bucket", "key", Region.EU_WEST_1);
            Exception ex = assertThrows(Exception.class, loader::downloadProceduresJson);
            assertTrue(ex.getMessage().contains("S3 error"));
        }
    }
}
