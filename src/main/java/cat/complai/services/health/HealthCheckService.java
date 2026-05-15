package cat.complai.services.health;

import cat.complai.config.SesConfiguration;
import cat.complai.helpers.openrouter.ProcedureRagHelperRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Performs individual, non-blocking health checks on the external dependencies
 * that the ComplAI service depends on at runtime.
 *
 * <p>Each dependency is checked independently via {@link CompletableFuture}
 * with a 5-second timeout, so a single slow or failing dependency never delays
 * the overall health response.
 *
 * <p>Results are returned as a {@code Map<String, Object>} with a consistent
 * structure: {@code {"status": true/false, "message": "..."}} for each check.
 */
@Singleton
public class HealthCheckService {

    private static final Logger logger = Logger.getLogger(HealthCheckService.class.getName());

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final S3Client s3Client;
    private final SqsClient sqsClient;
    private final SesConfiguration sesConfig;
    private final String complaintsBucket;
    private final String redactQueueUrl;
    private final String defaultCityId;
    private final ProcedureRagHelperRegistry procedureRegistry;
    private final String openRouterApiKey;

    /**
     * Protected no-arg constructor for test subclassing.
     * All fields are set to {@code null}; subclasses must override individual
     * check methods and/or provide their own AWS clients.
     */
    protected HealthCheckService() {
        this.complaintsBucket = null;
        this.redactQueueUrl = null;
        this.defaultCityId = null;
        this.sesConfig = null;
        this.procedureRegistry = null;
        this.openRouterApiKey = null;
        this.s3Client = null;
        this.sqsClient = null;
    }

    /**
     * Runs all dependency checks concurrently and returns the combined results.
     *
     * <p>Each check runs in its own {@link CompletableFuture} with a 5-second
     * timeout. Checks that time out or fail report their error status without
     * affecting the other checks.
     *
     * @return a map of check names to their results ({@code {"status": ..., "message": ...}})
     */
    public Map<String, Object> checkAll() {
        CompletableFuture<Map<String, Object>> s3Future = CompletableFuture
                .supplyAsync(this::checkS3)
                .orTimeout(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<Map<String, Object>> sqsFuture = CompletableFuture
                .supplyAsync(this::checkSQS)
                .orTimeout(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<Map<String, Object>> sesFuture = CompletableFuture
                .supplyAsync(this::checkSES)
                .orTimeout(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<Map<String, Object>> ragFuture = CompletableFuture
                .supplyAsync(this::checkRAG)
                .orTimeout(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        CompletableFuture<Map<String, Object>> openRouterFuture = CompletableFuture
                .supplyAsync(this::checkOpenRouter)
                .orTimeout(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        // Wait for all futures to complete (or timeout)
        try {
            CompletableFuture.allOf(s3Future, sqsFuture, sesFuture, ragFuture, openRouterFuture)
                    .get(DEFAULT_TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warning("Health check was interrupted");
        } catch (ExecutionException | TimeoutException | CancellationException e) {
            logger.log(Level.FINE, "One or more health checks did not complete in time", e);
        }

        Map<String, Object> results = new LinkedHashMap<>();
        results.put("s3", resolveFuture(s3Future, "S3 head bucket"));
        results.put("sqs", resolveFuture(sqsFuture, "SQS get queue attributes"));
        results.put("ses", resolveFuture(sesFuture, "SES configuration"));
        results.put("ragIndexes", resolveFuture(ragFuture, "RAG procedure index"));
        results.put("openRouterApiKeyConfigured", resolveFuture(openRouterFuture, "OpenRouter API key"));
        return results;
    }

    /**
     * Checks whether the complaints S3 bucket is reachable by calling
     * {@link S3Client#headBucket}.
     *
     * @return a map with {@code "status"} (boolean) and {@code "message"} describing
     *         the result
     */
    Map<String, Object> checkS3() {
        try {
            if (complaintsBucket == null || complaintsBucket.isBlank()) {
                return errorResult("S3 bucket name is not configured");
            }
            s3Client.headBucket(HeadBucketRequest.builder()
                    .bucket(complaintsBucket)
                    .build());
            logger.fine(() -> "S3 health check succeeded — bucket=" + complaintsBucket);
            return okResult("Bucket '" + complaintsBucket + "' is reachable");
        } catch (Exception e) {
            logger.log(Level.FINE, "S3 health check failed — bucket=" + complaintsBucket, e);
            return errorResult("Cannot access bucket '" + complaintsBucket + "': " + e.getMessage());
        }
    }

    /**
     * Checks whether the redact SQS queue is reachable by calling
     * {@link SqsClient#getQueueAttributes}.
     *
     * @return a map with {@code "status"} (boolean) and {@code "message"} describing
     *         the result
     */
    Map<String, Object> checkSQS() {
        try {
            if (redactQueueUrl == null || redactQueueUrl.isBlank()) {
                return errorResult("REDACT_QUEUE_URL is not configured");
            }
            sqsClient.getQueueAttributes(GetQueueAttributesRequest.builder()
                    .queueUrl(redactQueueUrl)
                    .attributeNamesWithStrings("All")
                    .build());
            logger.fine(() -> "SQS health check succeeded — queueUrl=" + redactQueueUrl);
            return okResult("Queue is reachable");
        } catch (Exception e) {
            logger.log(Level.FINE, "SQS health check failed — queueUrl=" + redactQueueUrl, e);
            return errorResult("Cannot access queue: " + e.getMessage());
        }
    }

    /**
     * Checks that the SES configuration is valid by verifying the
     * {@code AWS_SES_FROM_EMAIL} environment variable is set to a non-blank value.
     *
     * @return a map with {@code "status"} (boolean) and {@code "message"} describing
     *         the result
     */
    Map<String, Object> checkSES() {
        try {
            String fromEmail = sesConfig != null ? sesConfig.getFromEmail() : null;
            if (fromEmail == null || fromEmail.isBlank()) {
                return errorResult("AWS_SES_FROM_EMAIL is not configured");
            }
            logger.fine(() -> "SES health check succeeded — fromEmail is configured");
            return okResult("Sender email '" + fromEmail + "' is configured");
        } catch (Exception e) {
            logger.log(Level.FINE, "SES health check failed", e);
            return errorResult("SES configuration error: " + e.getMessage());
        }
    }

    /**
     * Checks that the RAG procedure index is loaded for the default city.
     *
     * @return a map with {@code "status"} (boolean), {@code "message"}, and
     *         the item count
     */
    Map<String, Object> checkRAG() {
        try {
            if (defaultCityId == null || defaultCityId.isBlank()) {
                return errorResult("Default city ID is not configured");
            }
            if (procedureRegistry == null) {
                return errorResult("ProcedureRagHelperRegistry is not available");
            }
            boolean loaded = procedureRegistry.isLoaded(defaultCityId);
            int itemCount = 0;
            if (loaded) {
                itemCount = procedureRegistry.getForCity(defaultCityId).getAll().size();
            }
            final int finalItemCount = itemCount;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", loaded);
            if (loaded) {
                result.put("message", "Procedure index loaded for city '" + defaultCityId + "' with " + finalItemCount + " items");
                result.put("items", finalItemCount);
            } else {
                result.put("message", "Procedure index not yet loaded for city '" + defaultCityId + "' (will load on first request)");
                result.put("items", 0);
            }
            logger.fine(() -> "RAG health check succeeded — city=" + defaultCityId + " loaded=" + loaded + " items=" + finalItemCount);
            return result;
        } catch (Exception e) {
            logger.log(Level.FINE, "RAG health check failed — city=" + defaultCityId, e);
            return errorResult("RAG index error for city '" + defaultCityId + "': " + e.getMessage());
        }
    }

    /**
     * Checks whether the OpenRouter API key is configured.
     *
     * @return a map with {@code "status"} (boolean) and {@code "message"}
     */
    Map<String, Object> checkOpenRouter() {
        boolean configured = openRouterApiKey != null && !openRouterApiKey.isBlank();
        if (configured) {
            return okResult("OPENROUTER_API_KEY is configured");
        }
        return errorResult("OPENROUTER_API_KEY is not configured");
    }

    /**
     * Closes the AWS SDK clients when the bean is destroyed.
     */
    @PreDestroy
    public void close() {
        if (s3Client != null) {
            try {
                s3Client.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to close S3Client", e);
            }
        }
        if (sqsClient != null) {
            try {
                sqsClient.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to close SqsClient", e);
            }
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Returns the result of a future if completed successfully, or an error
     * result if the future completed exceptionally or was cancelled.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveFuture(CompletableFuture<?> future, String checkName) {
        try {
            Object raw = future.get(1, TimeUnit.MILLISECONDS);
            if (raw instanceof Map) {
                return (Map<String, Object>) raw;
            }
            // Should never happen — all check methods return Map<String, Object>
            return errorResult("Unexpected result type from " + checkName + ": " + raw);
        } catch (TimeoutException e) {
            return errorResult("Check timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorResult("Check was interrupted");
        } catch (CancellationException e) {
            return errorResult("Check was cancelled");
        } catch (ExecutionException e) {
            return errorResult("Check failed: " + e.getCause().getMessage());
        }
    }

    private static Map<String, Object> okResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", true);
        result.put("message", message);
        return result;
    }

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", false);
        result.put("message", message);
        return result;
    }
}
