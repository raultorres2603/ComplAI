package cat.complai.services.stadistics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cat.complai.exceptions.ses.CloudWatchLogsException;
import cat.complai.services.stadistics.models.ComplaintFile;
import cat.complai.services.stadistics.models.FeedbackFile;
import cat.complai.services.stadistics.models.StadisticsModel;
import cat.complai.services.stadistics.models.StadisticsModel.ComparisonData;
import cat.complai.services.stadistics.models.StadisticsModel;
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
        // Delegate to city-specific method with null (all cities)
        return generateStadisticsReport(null);
    }

    /**
     * Generates a city-specific statistics report (stub implementation).
     *
     * @param cityId the city identifier (e.g., "elprat"), or null for all cities
     * @return the statistics model
     * @throws CloudWatchLogsException if CloudWatch is unavailable
     */
    public StadisticsModel generateStadisticsReport(String cityId) throws CloudWatchLogsException {
        logger.info("Generating statistics report with monthly year-to-date comparison (cityId={})...", cityId);

        ZoneId zone = ZoneId.of("Europe/Madrid");
        YearMonth currentYearMonth = YearMonth.now(zone);
        int currentYear = currentYearMonth.getYear();
        int currentMonthValue = currentYearMonth.getMonthValue();

        // Initialize yearly data array (January to current month)
        ArrayList<StadisticsModel.MonthlyData> yearlyData = new ArrayList<>();
        ArrayList<ComplaintFile> allComplaintFiles = new ArrayList<>();
        ArrayList<FeedbackFile> allFeedbackFiles = new ArrayList<>();

        // Fetch data for each month from January to current month
        for (int month = 1; month <= currentMonthValue; month++) {
            YearMonth ym = YearMonth.of(currentYear, month);
            String monthLabel = ym.atDay(1).format(
                java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", new java.util.Locale("ca", "ES"))
            );
            monthLabel = monthLabel.substring(0, 1).toUpperCase() + monthLabel.substring(1);

            Instant monthStart = ym.atDay(1).atStartOfDay(zone).toInstant();
            Instant monthEnd;

            if (month == currentMonthValue) {
                // Current month: use now as end (in-progress)
                monthEnd = Instant.now();
            } else {
                // Past months: use end of month
                monthEnd = ym.atEndOfMonth().atStartOfDay(zone).toInstant().plus(1, ChronoUnit.DAYS);
            }

            int ask = totalAskInteractions(monthStart, monthEnd);
            int redact = totalRedactInteractions(monthStart, monthEnd);
            int feedback = totalFeedbacks(monthStart, monthEnd);
            ArrayList<ComplaintFile> complaintFiles = getComplaintFiles(monthStart, monthEnd);
            ArrayList<FeedbackFile> feedbackFiles = getFeedbackFiles(monthStart, monthEnd);

            yearlyData.add(new StadisticsModel.MonthlyData(monthLabel, ask, redact, feedback, complaintFiles, feedbackFiles));

            // Accumulate for legacy total
            allComplaintFiles.addAll(complaintFiles);
            allFeedbackFiles.addAll(feedbackFiles);
        }

        // Get current month and previous month for comparison
        StadisticsModel.MonthlyData currentMonthData = yearlyData.get(yearlyData.size() - 1);
        StadisticsModel.MonthlyData previousMonthData = null;
        if (yearlyData.size() > 1) {
            previousMonthData = yearlyData.get(yearlyData.size() - 2);
        }

        // Calculate comparisons (current month vs previous month)
        ComparisonData askComparison = calculateComparison(
            previousMonthData != null ? previousMonthData.getAskInteractions() : 0,
            currentMonthData.getAskInteractions()
        );
        ComparisonData redactComparison = calculateComparison(
            previousMonthData != null ? previousMonthData.getRedactInteractions() : 0,
            currentMonthData.getRedactInteractions()
        );
        ComparisonData feedbackComparison = calculateComparison(
            previousMonthData != null ? previousMonthData.getFeedbackCount() : 0,
            currentMonthData.getFeedbackCount()
        );

        // Legacy totals (current month only for backward compatibility)
        int totalAsk = currentMonthData.getAskInteractions();
        int totalRedact = currentMonthData.getRedactInteractions();
        int totalFeedback = currentMonthData.getFeedbackCount();

        StadisticsModel report = new StadisticsModel(totalAsk, totalRedact, totalFeedback, allComplaintFiles, allFeedbackFiles);

        // Set monthly comparison data
        report.setCurrentMonth(currentMonthData);
        report.setPreviousMonth(previousMonthData);
        report.setAskComparison(askComparison);
        report.setRedactComparison(redactComparison);
        report.setFeedbackComparison(feedbackComparison);
        report.setYearlyData(yearlyData);

        logger.info("Statistics report generated with monthly year-to-date comparison: {}", report);
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
