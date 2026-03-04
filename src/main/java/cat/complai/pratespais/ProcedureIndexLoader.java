package cat.complai.pratespais;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class ProcedureIndexLoader {
    private final String bucket;
    private final String key;
    private final Region region;

    public ProcedureIndexLoader(String bucket, String key, Region region) {
        this.bucket = bucket;
        this.key = key;
        this.region = region;
    }

    public Path downloadProceduresJson() throws Exception {
        S3Client s3 = S3Client.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        Path tempFile = Files.createTempFile("procedures", ".json");
        try (InputStream in = s3.getObject(req)) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }
}

