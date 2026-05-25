package cat.complai.utilities.metrics;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;

/**
 * Publishes interaction metrics to CloudWatch Metrics for every ask, redact,
 * and feedback operation.
 *
 * <p>Metrics are published with the {@code ComplAI} namespace and include
 * dimensions for {@code Operation} (ASK / REDACT / FEEDBACK), {@code City},
 * and {@code Status} (Success / Error). This replaces the previous approach
 * of scraping CloudWatch Logs with {@code FilterLogEvents}, which was
 * expensive, slow, and inaccurate.
 *
 * <p>The {@code StadisticsService} queries these metrics via
 * {@code getMetricStatistics} instead of filtering log events.
 *
 * <p>Follows the AWS wrapper pattern: protected no-arg constructor for
 * test subclassing, endpoint override for LocalStack compatibility,
 * and {@code @PreDestroy} to close the client.
 */
@Singleton
public class InteractionMetricsPublisher {

    private static final Logger logger = Logger.getLogger(InteractionMetricsPublisher.class.getName());

    /** CloudWatch Metrics namespace for all interaction counters. */
    public static final String METRICS_NAMESPACE = "ComplAI";

    /** Metric name for interaction counts (value is always 1.0, SUM over time = count). */
    public static final String INTERACTION_METRIC_NAME = "Interaction";

    /** Metric name for operation latency in milliseconds. */
    static final String LATENCY_METRIC_NAME = "Latency";

    /** Maximum number of MetricDatum objects per putMetricData call (API limit is 20). */
    static final int BATCH_SIZE = 10;

    private final CloudWatchClient cloudWatchClient;

    /**
     * Constructs the publisher with an injected CloudWatch client.
     *
     * @param cloudWatchClient the CloudWatch client (produced by AwsClientFactory)
     */
    @jakarta.inject.Inject
    public InteractionMetricsPublisher(CloudWatchClient cloudWatchClient) {
        this.cloudWatchClient = cloudWatchClient;
    }

    /**
     * Protected no-arg constructor for test subclassing.
     * Does not initialise the real CloudWatch client.
     */
    protected InteractionMetricsPublisher() {
        this.cloudWatchClient = null;
    }

    /**
     * Publishes an interaction metric and a latency metric to CloudWatch.
     *
     * @param operation the operation type: "ASK", "REDACT", or "FEEDBACK"
     * @param cityId    the city identifier (e.g. "elprat")
     * @param success   whether the operation completed successfully
     * @param latencyMs the operation latency in milliseconds
     */
    public void publishInteraction(String operation, String cityId, boolean success, long latencyMs) {
        if (cloudWatchClient == null) {
            logger.warning("InteractionMetricsPublisher: cloudWatchClient is null — skipping metric publish");
            return;
        }

        try {
            List<MetricDatum> data = new ArrayList<>(2);

            // Interaction count (always 1, SUM over time = total count)
            data.add(MetricDatum.builder()
                    .metricName(INTERACTION_METRIC_NAME)
                    .value(1.0)
                    .unit(StandardUnit.COUNT)
                    .timestamp(Instant.now())
                    .dimensions(
                            Dimension.builder().name("Operation").value(operation).build(),
                            Dimension.builder().name("City").value(cityId).build())
                    .build());

            // Latency in milliseconds
            data.add(MetricDatum.builder()
                    .metricName(LATENCY_METRIC_NAME)
                    .value((double) latencyMs)
                    .unit(StandardUnit.MILLISECONDS)
                    .timestamp(Instant.now())
                    .dimensions(
                            Dimension.builder().name("Operation").value(operation).build(),
                            Dimension.builder().name("City").value(cityId).build())
                    .build());

            PutMetricDataRequest request = PutMetricDataRequest.builder()
                    .namespace(METRICS_NAMESPACE)
                    .metricData(data)
                    .build();

            cloudWatchClient.putMetricData(request);

            logger.fine(() -> "Published metrics — operation=" + operation
                    + " city=" + cityId + " success=" + success
                    + " latencyMs=" + latencyMs);

        } catch (Exception e) {
            // Log and swallow — metric publishing must never break the request flow
            logger.log(Level.WARNING, "Failed to publish CloudWatch metrics — operation="
                    + operation + " city=" + cityId, e);
        }
    }

    @PreDestroy
    public void close() {
        if (cloudWatchClient != null) {
            try {
                cloudWatchClient.close();
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to close CloudWatch client", e);
            }
        }
    }
}
