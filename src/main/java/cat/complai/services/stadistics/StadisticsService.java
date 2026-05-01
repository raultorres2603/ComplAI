package cat.complai.services.stadistics;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cat.complai.services.stadistics.models.StadisticsModel;
import jakarta.inject.Singleton;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;

@Singleton
public class StadisticsService implements IStadisticsService {

    private final Logger logger = LoggerFactory.getLogger(StadisticsService.class.getName());
    private final String logGroupAsk = "/aws/lambda/ComplAILambda-" + System.getenv("ENVIRONMENT");
    private final String logGroupRedact = "/aws/lambda/ComplAIRedactLambda-" + System.getenv("ENVIRONMENT");
    private final String logGroupFeedback = "/aws/lambda/ComplAIFeedbackWorkerLambda-" + System.getenv("ENVIRONMENT");

    private int totalRedactInteractions() {
        logger.info("Fetching total redact interactions from CloudWatch Logs for log group: {}", logGroupRedact);
        // get the total redact interactions from cloudwatch logs
        try (CloudWatchLogsClient logsClient = CloudWatchLogsClient.builder()
                .region(Region.EU_WEST_1) // Change to your region
                .build()) {

            // Define the time range (e.g., the last 1 hour)
            long startTime = Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli();
            long endTime = Instant.now().toEpochMilli();

            // Build the request
            FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                    .logGroupName(logGroupRedact)
                    .startTime(startTime)
                    .endTime(endTime)
                    .filterPattern("POST /complai/redact received") // Optional: Only fetch logs containing specific
                    .build();

            // Fetch the logs
            try {
                FilterLogEventsResponse response = logsClient.filterLogEvents(request);
                int totalInteractions = response.events().size();
                logger.info("Total redact interactions found: {}", totalInteractions);
                return totalInteractions;
            } catch (Exception e) {
                logger.error("Error fetching redact interactions from CloudWatch Logs: {}", e.getMessage());
                throw new RuntimeException("Error fetching redact interactions from CloudWatch Logs", e);
            }
        } catch (Exception e) {
            logger.error("Error fetching redact interactions from CloudWatch Logs: {}", e.getMessage());
            throw new RuntimeException("Error fetching redact interactions from CloudWatch Logs", e);
        }
    }

    private int totalFeedbacks() {
        logger.info("Fetching total feedback interactions from CloudWatch Logs for log group: {}", logGroupFeedback);
        // get the total feedback interactions from cloudwatch logs
        try (CloudWatchLogsClient logsClient = CloudWatchLogsClient.builder()
                .region(Region.EU_WEST_1) // Change to your region
                .build()) {

            // Define the time range (e.g., the last 1 hour)
            long startTime = Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli();
            long endTime = Instant.now().toEpochMilli();

            // Build the request
            FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                    .logGroupName(logGroupFeedback)
                    .startTime(startTime)
                    .endTime(endTime)
                    .filterPattern("POST /complai/feedback received") // Optional: Only fetch logs containing specific
                    .build();

            // Fetch the logs
            try {
                FilterLogEventsResponse response = logsClient.filterLogEvents(request);
                int totalInteractions = response.events().size();
                logger.info("Total feedback interactions found: {}", totalInteractions);
                return totalInteractions;
            } catch (Exception e) {
                logger.error("Error fetching feedback interactions from CloudWatch Logs: {}", e.getMessage());
                throw new RuntimeException("Error fetching feedback interactions from CloudWatch Logs", e);
            }
        } catch (Exception e) {
            logger.error("Error fetching feedback interactions from CloudWatch Logs: {}", e.getMessage());
            throw new RuntimeException("Error fetching feedback interactions from CloudWatch Logs", e);
        }
    }

    private int totalAskInteractions() {
        logger.info("Fetching total ask interactions from CloudWatch Logs for log group: {}", logGroupAsk);
        // get the total ask interactions from cloudwatch logs
        try (CloudWatchLogsClient logsClient = CloudWatchLogsClient.builder()
                .region(Region.EU_WEST_1) // Change to your region
                .build()) {

            // Define the time range (e.g., the last 1 hour)
            long startTime = Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli();
            long endTime = Instant.now().toEpochMilli();

            // Build the request
            FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                    .logGroupName(logGroupAsk)
                    .startTime(startTime)
                    .endTime(endTime)
                    .filterPattern("POST /complai/ask (stream) received") // Optional: Only fetch logs containing
                    .build();

            // Fetch the logs
            try {
                FilterLogEventsResponse response = logsClient.filterLogEvents(request);
                int totalInteractions = response.events().size();
                logger.info("Total ask interactions found: {}", totalInteractions);
                return totalInteractions;
            } catch (Exception e) {
                logger.error("Error fetching ask interactions from CloudWatch Logs: {}", e.getMessage());
                throw new RuntimeException("Error fetching ask interactions from CloudWatch Logs", e);
            }
        } catch (Exception e) {
            logger.error("Error fetching ask interactions from CloudWatch Logs: {}", e.getMessage());
            throw new RuntimeException("Error fetching ask interactions from CloudWatch Logs", e);
        }
    }

    @Override
    public StadisticsModel generateStadisticsReport() {
        logger.info("Generating statistics report...");
        int totalAsk = totalAskInteractions();
        int totalRedact = totalRedactInteractions();
        int totalFeedback = totalFeedbacks();

        StadisticsModel report = new StadisticsModel(totalAsk, totalRedact, totalFeedback);
        logger.info("Statistics report generated: {}", report);
        return report;
    }

}
