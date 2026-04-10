package cat.complai.scrapper;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Downloads a procedures JSON file from S3 for offline index building.
 *
 * <p>Used by the scraper tooling (outside the Lambda runtime) to pull the latest
 * procedure corpus from S3, process it, and re-upload an updated version. It is not
 * used at Lambda runtime — the Lambda loads procedures directly from S3 via
 * {@link cat.complai.openrouter.helpers.ProcedureRagHelper}.
 */
public class ProcedureIndexLoader {
    private final String bucket;
    private final String key;
    private final Region region;

    public ProcedureIndexLoader(String bucket, String key, Region region) {
        this.bucket = bucket;
        this.key = key;
        this.region = region;
    }

    /**
     * Downloads the procedures JSON file from the configured S3 bucket and key.
     *
     * @return the local {@link Path} of the temporary file containing the downloaded JSON
     * @throws Exception if the S3 download fails or the temporary file cannot be created
     */
    public Path downloadProceduresJson() throws Exception {
        S3Client s3 = S3Client.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
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
