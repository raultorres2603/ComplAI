package cat.complai.config;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.util.logging.Logger;

@Factory
public class AwsClientFactory {

    private static final Logger LOGGER = Logger.getLogger(AwsClientFactory.class.getName());

    private final Region region;
    private final String endpointUrl;

    @Inject
    public AwsClientFactory(
            @Value("${AWS_REGION:eu-west-1}") String regionStr,
            @Value("${AWS_ENDPOINT_URL:}") String endpointUrl) {
        this.region = Region.of(regionStr);
        this.endpointUrl = endpointUrl;
    }

    @Bean
    @Singleton
    @Primary
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(region)
                .httpClient(UrlConnectionHttpClient.builder().build())
                .credentialsProvider(DefaultCredentialsProvider.builder().build());
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            LOGGER.fine(() -> "Overriding S3 endpoint to " + endpointUrl);
            builder.endpointOverride(URI.create(endpointUrl));
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build());
        }
        return builder.build();
    }

    @Bean
    @Singleton
    @Primary
    public S3Presigner s3Presigner() {
        var builder = S3Presigner.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.builder().build());
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            LOGGER.fine(() -> "Overriding S3 Presigner endpoint to " + endpointUrl);
            builder.endpointOverride(URI.create(endpointUrl));
        }
        return builder.build();
    }

    @Bean
    @Singleton
    @Primary
    public SqsClient sqsClient() {
        var builder = SqsClient.builder()
                .region(region)
                .httpClient(UrlConnectionHttpClient.builder().build())
                .credentialsProvider(DefaultCredentialsProvider.builder().build());
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            LOGGER.fine(() -> "Overriding SQS endpoint to " + endpointUrl);
            builder.endpointOverride(URI.create(endpointUrl));
        }
        return builder.build();
    }

    @Bean
    @Singleton
    @Primary
    public CloudWatchClient cloudWatchClient() {
        var builder = CloudWatchClient.builder()
                .region(region)
                .httpClient(UrlConnectionHttpClient.builder().build())
                .credentialsProvider(DefaultCredentialsProvider.builder().build());
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            LOGGER.fine(() -> "Overriding CloudWatch endpoint to " + endpointUrl);
            builder.endpointOverride(URI.create(endpointUrl));
        }
        return builder.build();
    }

    @Bean
    @Singleton
    @Primary
    public SesClient sesClient() {
        var builder = SesClient.builder()
                .region(region)
                .httpClient(UrlConnectionHttpClient.builder().build())
                .credentialsProvider(DefaultCredentialsProvider.builder().build());
        if (endpointUrl != null && !endpointUrl.isBlank()) {
            LOGGER.fine(() -> "Overriding SES endpoint to " + endpointUrl);
            builder.endpointOverride(URI.create(endpointUrl));
        }
        return builder.build();
    }
}
