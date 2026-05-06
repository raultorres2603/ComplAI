package cat.complai.services.stadistics;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cat.complai.exceptions.ses.CloudWatchLogsException;
import cat.complai.services.stadistics.models.ComplaintFile;
import cat.complai.services.stadistics.models.FeedbackFile;
import cat.complai.services.stadistics.models.StadisticsModel;
import cat.complai.services.stadistics.models.StadisticsModel.ComparisonData;
import cat.complai.services.stadistics.models.StadisticsModel.WeeklyData;
import cat.complai.utilities.s3.S3ComplaintLister;
import cat.complai.utilities.s3.S3FeedbackLister;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.ServiceUnavailableException;

@Singleton
public class StadisticsService implements IStadisticsService {

    private final Logger logger = LoggerFactory.getLogger(StadisticsService.class.getName());
    private final String logGroupAsk = "/aws/lambda/ComplAILambda-" + System.getenv("ENVIRONMENT");
    private final String logGroupRedact = "/aws/lambda/ComplAIRedactorLambda-" + System.getenv("ENVIRONMENT");
    private final String logGroupFeedback = "/aws/lambda/ComplAIFeedbackWorkerLambda-" + System.getenv("ENVIRONMENT");

    private final S3ComplaintLister s3ComplaintLister;
    private final S3FeedbackLister s3FeedbackLister;

    @Inject
    public StadisticsService(S3ComplaintLister s3ComplaintLister, S3FeedbackLister s3FeedbackLister) {
        this.s3ComplaintLister = s3ComplaintLister;
        this.s3FeedbackLister = s3FeedbackLister;
    }

    // ======================
    // CloudWatch Logs Methods (with date range support)
    // ======================

    private int totalRedactInteractions(Instant from, Instant to) {
        logger.info("Fetching total redact interactions from CloudWatch Logs for log group: {} from={} to={}", logGroupRedact, from, to);
        try (CloudWatchLogsClient logsClient = CloudWatchLogsClient.builder()
                .region(Region.EU_WEST_1)
                .build()) {

            long startTime = from.toEpochMilli();
            long endTime = to.toEpochMilli();

            FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                    .logGroupName(logGroupRedact)
                    .startTime(startTime)
                    .endTime(endTime)
                    .build();

            try {
                FilterLogEventsResponse response = logsClient.filterLogEvents(request);
                int totalInteractions = response.events().size();
                logger.info("Total redact interactions found: {}", totalInteractions);
                return totalInteractions;
            } catch (ServiceUnavailableException e) {
                logger.error("CloudWatch Logs service is currently unavailable: {}", e.getMessage());
                throw new CloudWatchLogsException("CloudWatch Logs service is currently unavailable", e);
            } catch (Exception e) {
                logger.error("Error filtering redact interactions from CloudWatch Logs: {}", e.getMessage());
                throw new CloudWatchLogsException("Error filtering redact interactions from CloudWatch Logs", e);
            }
        } catch (Exception e) {
            logger.error("Error building CloudWatch connection for redact interactions: {}", e.getMessage());
            throw new CloudWatchLogsException("Error building CloudWatch connection", e);
        }
    }

    private int totalFeedbacks(Instant from, Instant to) {
        logger.info("Fetching total feedback interactions from CloudWatch Logs for log group: {} from={} to={}", logGroupFeedback, from, to);
        try (CloudWatchLogsClient logsClient = CloudWatchLogsClient.builder()
                .region(Region.EU_WEST_1)
                .build()) {

            long startTime = from.toEpochMilli();
            long endTime = to.toEpochMilli();

            FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                    .logGroupName(logGroupFeedback)
                    .startTime(startTime)
                    .endTime(endTime)
                    .build();

            try {
                FilterLogEventsResponse response = logsClient.filterLogEvents(request);
                int totalInteractions = response.events().size();
                logger.info("Total feedback interactions found: {}", totalInteractions);
                return totalInteractions;
            } catch (ServiceUnavailableException e) {
                logger.error("CloudWatch Logs service is currently unavailable: {}", e.getMessage());
                throw new CloudWatchLogsException("CloudWatch Logs service is currently unavailable", e);
            } catch (Exception e) {
                logger.error("Error filtering feedback interactions from CloudWatch Logs: {}", e.getMessage());
                throw new CloudWatchLogsException("Error filtering feedback interactions from CloudWatch Logs", e);
            }
        } catch (Exception e) {
            logger.error("Error building CloudWatch connection for feedback interactions: {}", e.getMessage());
            throw new CloudWatchLogsException("Error building CloudWatch connection", e);
        }
    }

    private int totalAskInteractions(Instant from, Instant to) {
        logger.info("Fetching total ask interactions from CloudWatch Logs for log group: {} from={} to={}", logGroupAsk, from, to);
        try (CloudWatchLogsClient logsClient = CloudWatchLogsClient.builder()
                .region(Region.EU_WEST_1)
                .build()) {

            long startTime = from.toEpochMilli();
            long endTime = to.toEpochMilli();

            FilterLogEventsRequest request = FilterLogEventsRequest.builder()
                    .logGroupName(logGroupAsk)
                    .startTime(startTime)
                    .endTime(endTime)
                    .build();

            try {
                FilterLogEventsResponse response = logsClient.filterLogEvents(request);
                int totalInteractions = response.events().size();
                logger.info("Total ask interactions found: {}", totalInteractions);
                return totalInteractions;
            } catch (ServiceUnavailableException e) {
                logger.error("CloudWatch Logs service is currently unavailable: {}", e.getMessage());
                throw new CloudWatchLogsException("CloudWatch Logs service is currently unavailable", e);
            } catch (Exception e) {
                logger.error("Error filtering ask interactions from CloudWatch Logs: {}", e.getMessage());
                throw new CloudWatchLogsException("Error filtering ask interactions from CloudWatch Logs", e);
            }
        } catch (Exception e) {
            logger.error("Error building CloudWatch connection: {}", e.getMessage());
            throw new CloudWatchLogsException("Error building CloudWatch connection", e);
        }
    }

    // ======================
    // S3 File Methods (with date range support)
    // ======================

    private ArrayList<ComplaintFile> getComplaintFiles(Instant from, Instant to) {
        logger.info("Fetching complaint files from S3 from={} to={}", from, to);

        try {
            ArrayList<ComplaintFile> files = new ArrayList<>();
            var entries = s3ComplaintLister.listComplaintFiles(from, to);

            for (S3ComplaintLister.ComplaintFileEntry entry : entries) {
                java.net.URL url = new java.net.URL(entry.getUrl());
                files.add(new ComplaintFile(entry.getFileName(), url));
            }

            logger.info("Found {} complaint files from S3", files.size());
            return files;
        } catch (Exception e) {
            logger.error("Error fetching complaint files from S3: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch complaint files: " + e.getMessage(), e);
        }
    }

    private ArrayList<FeedbackFile> getFeedbackFiles(Instant from, Instant to) {
        logger.info("Fetching feedback files from S3 from={} to={}", from, to);

        try {
            ArrayList<FeedbackFile> files = new ArrayList<>();
            var entries = s3FeedbackLister.listAllFeedbackFiles(from, to);

            for (S3FeedbackLister.FeedbackFileEntry entry : entries) {
                java.net.URL url = new java.net.URL(entry.getUrl());
                files.add(new FeedbackFile(entry.getFileName(), url));
            }

            logger.info("Found {} feedback files from S3", files.size());
            return files;
        } catch (Exception e) {
            logger.error("Error fetching feedback files from S3: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch feedback files: " + e.getMessage(), e);
        }
    }

    // ======================
    // Main Report Generation
    // ======================

    @Override
    public StadisticsModel generateStadisticsReport() throws CloudWatchLogsException {
        logger.info("Generating statistics report with weekly comparison...");

        // Calculate date ranges
        Instant now = Instant.now();
        Instant currentWeekStart = now.minus(7, ChronoUnit.DAYS);  // Last 7 days
        Instant previousWeekStart = now.minus(14, ChronoUnit.DAYS); // Days 8-14 ago
        Instant previousWeekEnd = now.minus(7, ChronoUnit.DAYS);    // End of previous week

        // Fetch current week data (last 7 days)
        int currentAsk = totalAskInteractions(currentWeekStart, now);
        int currentRedact = totalRedactInteractions(currentWeekStart, now);
        int currentFeedback = totalFeedbacks(currentWeekStart, now);
        ArrayList<ComplaintFile> currentComplaintFiles = getComplaintFiles(currentWeekStart, now);
        ArrayList<FeedbackFile> currentFeedbackFiles = getFeedbackFiles(currentWeekStart, now);

        WeeklyData currentWeek = new WeeklyData(
            currentAsk, currentRedact, currentFeedback,
            currentComplaintFiles, currentFeedbackFiles
        );

        // Fetch previous week data (days 8-14)
        int previousAsk = totalAskInteractions(previousWeekStart, previousWeekEnd);
        int previousRedact = totalRedactInteractions(previousWeekStart, previousWeekEnd);
        int previousFeedback = totalFeedbacks(previousWeekStart, previousWeekEnd);
        ArrayList<ComplaintFile> previousComplaintFiles = getComplaintFiles(previousWeekStart, previousWeekEnd);
        ArrayList<FeedbackFile> previousFeedbackFiles = getFeedbackFiles(previousWeekStart, previousWeekEnd);

        WeeklyData previousWeek = new WeeklyData(
            previousAsk, previousRedact, previousFeedback,
            previousComplaintFiles, previousFeedbackFiles
        );

        // Calculate comparisons
        ComparisonData askComparison = calculateComparison(previousAsk, currentAsk);
        ComparisonData redactComparison = calculateComparison(previousRedact, currentRedact);
        ComparisonData feedbackComparison = calculateComparison(previousFeedback, currentFeedback);

        // Build legacy single-week data for backward compatibility
        // Use current week as the "total" for backward compatibility
        ArrayList<ComplaintFile> allComplaintFiles = new ArrayList<>(currentComplaintFiles);
        allComplaintFiles.addAll(previousComplaintFiles);
        ArrayList<FeedbackFile> allFeedbackFiles = new ArrayList<>(currentFeedbackFiles);
        allFeedbackFiles.addAll(previousFeedbackFiles);

        StadisticsModel report = new StadisticsModel(currentAsk, currentRedact, currentFeedback, allComplaintFiles, allFeedbackFiles);

        // Set weekly comparison data
        report.setCurrentWeek(currentWeek);
        report.setPreviousWeek(previousWeek);
        report.setAskComparison(askComparison);
        report.setRedactComparison(redactComparison);
        report.setFeedbackComparison(feedbackComparison);

        logger.info("Statistics report generated with weekly comparison: {}", report);
        return report;
    }

    // ======================
    // Helper Methods
    // ======================

    /**
     * Calculates comparison between previous and current values.
     * @param previousValue the previous week's value
     * @param currentValue the current week's value
     * @return ComparisonData with absolute difference and percentage change
     */
    private ComparisonData calculateComparison(int previousValue, int currentValue) {
        int absoluteDifference = currentValue - previousValue;
        double percentageChange = 0.0;

        if (previousValue != 0) {
            percentageChange = ((double) absoluteDifference / previousValue) * 100.0;
        } else if (currentValue != 0) {
            // If previous was 0 and current is not, consider it as 100% increase
            percentageChange = 100.0;
        }
        // If both are 0, percentage change is 0

        return new ComparisonData(absoluteDifference, percentageChange);
    }

}
