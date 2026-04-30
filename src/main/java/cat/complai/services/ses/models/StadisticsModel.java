package cat.complai.services.ses.models;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Inject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;

@Introspected
public class StadisticsModel {

    @Inject
    private final static Logger logger = LoggerFactory.getLogger(StadisticsModel.class.getName());

    private int totalAskInteractions;
    private int totalFeedbacks;
    private int totalRedactInteractions;
    private final String logGroupAsk = "/aws/lambda/ComplAILambda-" + System.getenv("ENVIRONMENT");
    private final String logGroupRedact = "/aws/lambda/ComplAIRedactLambda-" + System.getenv("ENVIRONMENT");
    private final String logGroupFeedback = "/aws/lambda/ComplAIFeedbackWorkerLambda-" + System.getenv("ENVIRONMENT");

    public StadisticsModel() {
        this.totalAskInteractions = totalAskInteractions();
        this.totalFeedbacks = totalFeedbacks();
        this.totalRedactInteractions = totalRedactInteractions();
    }

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
                return 0; // Return 0 or handle as needed
            }
        } catch (Exception e) {
            logger.error("Error fetching redact interactions from CloudWatch Logs: {}", e.getMessage());
            return 0; // Return 0 or handle as needed
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
                return 0; // Return 0 or handle as needed
            }
        } catch (Exception e) {
            logger.error("Error fetching feedback interactions from CloudWatch Logs: {}", e.getMessage());
            return 0; // Return 0 or handle as needed
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
                                                                          // specific
                    .build();

            // Fetch the logs
            try {
                FilterLogEventsResponse response = logsClient.filterLogEvents(request);
                int totalInteractions = response.events().size();
                logger.info("Total ask interactions found: {}", totalInteractions);
                return totalInteractions;
            } catch (Exception e) {
                logger.error("Error fetching ask interactions from CloudWatch Logs: {}", e.getMessage());
                return 0; // Return 0 or handle as needed
            }
        } catch (Exception e) {
            logger.error("Error fetching ask interactions from CloudWatch Logs: {}", e.getMessage());
            return 0; // Return 0 or handle as needed
        }
    }
    public int getTotalAskInteractions() {
        return totalAskInteractions;
    }

    public int getTotalFeedbacks() {
        return totalFeedbacks;
    }

    public int getTotalRedactInteractions() {
        return totalRedactInteractions;
    }

    @Override
    public String toString() {
        // Build a block of text with the statistics
        StringBuilder sb = new StringBuilder();
        sb.append("Stadistics Report:\n");
        sb.append("Total Ask Interactions: ").append(totalAskInteractions).append("\n");
        sb.append("Total Feedbacks: ").append(totalFeedbacks).append("\n");
        sb.append("Total Redact Interactions: ").append(totalRedactInteractions).append("\n");
        return sb.toString();
    }
}
