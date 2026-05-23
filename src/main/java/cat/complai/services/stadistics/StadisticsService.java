package cat.complai.services.stadistics;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.exceptions.ses.CloudWatchLogsException;
import cat.complai.services.openrouter.IOpenRouterService;
import cat.complai.services.stadistics.models.ComplaintFile;
import cat.complai.services.stadistics.models.FeedbackFile;
import cat.complai.services.stadistics.models.StadisticsModel;
import cat.complai.services.stadistics.models.StadisticsModel.MonthlyData;
import cat.complai.services.stadistics.models.StadisticsModel.ComparisonData;
import cat.complai.utilities.metrics.InteractionMetricsPublisher;
import cat.complai.utilities.s3.S3ComplaintLister;
import cat.complai.utilities.s3.S3FeedbackLister;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

@Singleton
public class StadisticsService implements IStadisticsService {

    private final Logger logger = LoggerFactory.getLogger(StadisticsService.class.getName());

    private final S3ComplaintLister s3ComplaintLister;
    private final S3FeedbackLister s3FeedbackLister;
    private final IOpenRouterService openRouterService;
    private final CloudWatchClient cloudWatchClient;

    @Inject
    public StadisticsService(S3ComplaintLister s3ComplaintLister, S3FeedbackLister s3FeedbackLister,
            IOpenRouterService openRouterService, CloudWatchClient cloudWatchClient) {
        this.s3ComplaintLister = s3ComplaintLister;
        this.s3FeedbackLister = s3FeedbackLister;
        this.openRouterService = openRouterService;
        this.cloudWatchClient = cloudWatchClient;
    }

    // ======================
    // CloudWatch Metrics Methods (replaces previous FilterLogEvents calls)
    // ======================

    /**
     * Queries the SUM of Interaction metrics from CloudWatch Metrics for a specific
     * operation and time range.
     *
     * @param operation the operation type: "ASK", "REDACT", or "FEEDBACK"
     * @param cityId    the city identifier, or null for all cities
     * @param from      the start of the time range (inclusive)
     * @param to        the end of the time range (exclusive)
     * @return the total count of interactions matching the criteria
     * @throws CloudWatchLogsException if CloudWatch Metrics is unavailable
     */
    private int queryInteractionCount(String operation, String cityId, Instant from, Instant to) {
        logger.info("Querying CloudWatch Metrics — operation={} cityId={} from={} to={}",
                operation, cityId, from, to);

        try {
            // Period must cover the entire range in seconds
            long rangeSeconds = Duration.between(from, to).getSeconds();
            if (rangeSeconds <= 0) {
                rangeSeconds = 60; // minimum 1 minute
            }
            // CloudWatch requires Period to be a multiple of 60 seconds.
            // Round up so the full time range is covered by a single period.
            if (rangeSeconds % 60 != 0) {
                rangeSeconds = ((rangeSeconds / 60) + 1) * 60;
            }

            GetMetricStatisticsRequest.Builder requestBuilder = GetMetricStatisticsRequest.builder()
                    .namespace(InteractionMetricsPublisher.METRICS_NAMESPACE)
                    .metricName(InteractionMetricsPublisher.INTERACTION_METRIC_NAME)
                    .startTime(from)
                    .endTime(to)
                    .period((int) Math.min(rangeSeconds, Integer.MAX_VALUE))
                    .statistics(Statistic.SUM);

            // Always add Operation dimension
            requestBuilder.dimensions(
                    Dimension.builder().name("Operation").value(operation).build());

            // Add City dimension if filtering by city
            if (cityId != null && !cityId.isBlank()) {
                requestBuilder.dimensions(
                        Dimension.builder().name("Operation").value(operation).build(),
                        Dimension.builder().name("City").value(cityId).build());
            }

            GetMetricStatisticsResponse response = cloudWatchClient.getMetricStatistics(requestBuilder.build());

            if (response.datapoints() == null || response.datapoints().isEmpty()) {
                logger.debug("No CloudWatch Metrics datapoints found — operation={} cityId={}", operation, cityId);
                return 0;
            }

            // Sum all datapoints (there should be only one with SUM + period=range)
            double total = response.datapoints().stream()
                    .mapToDouble(dp -> dp.sum() != null ? dp.sum() : 0.0)
                    .sum();

            int result = (int) Math.round(total);
            logger.info("CloudWatch Metrics query result — operation={} cityId={} count={}",
                    operation, cityId, result);
            return result;

        } catch (Exception e) {
            logger.error("Failed to query CloudWatch Metrics — operation={} cityId={}: {}",
                    operation, cityId, e.getMessage(), e);
            throw new CloudWatchLogsException("Failed to query CloudWatch Metrics for operation="
                    + operation + " cityId=" + cityId, e);
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
                java.net.URL url = java.net.URI.create(entry.url()).toURL();
                files.add(new ComplaintFile(entry.fileName(), url));
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
                java.net.URL url = java.net.URI.create(entry.url()).toURL();
                files.add(new FeedbackFile(entry.fileName(), url));
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
                    java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale.of("ca", "ES")));
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

            int ask = queryInteractionCount("ASK", cityId, monthStart, monthEnd);
            int redact = queryInteractionCount("REDACT", cityId, monthStart, monthEnd);
            int feedback = queryInteractionCount("FEEDBACK", cityId, monthStart, monthEnd);
            ArrayList<ComplaintFile> complaintFiles = getComplaintFiles(monthStart, monthEnd);
            ArrayList<FeedbackFile> feedbackFiles = getFeedbackFiles(monthStart, monthEnd);

            yearlyData.add(
                    new StadisticsModel.MonthlyData(monthLabel, ask, redact, feedback, complaintFiles, feedbackFiles));

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
                currentMonthData.getAskInteractions());
        ComparisonData redactComparison = calculateComparison(
                previousMonthData != null ? previousMonthData.getRedactInteractions() : 0,
                currentMonthData.getRedactInteractions());
        ComparisonData feedbackComparison = calculateComparison(
                previousMonthData != null ? previousMonthData.getFeedbackCount() : 0,
                currentMonthData.getFeedbackCount());

        // Legacy totals (current month only for backward compatibility)
        int totalAsk = currentMonthData.getAskInteractions();
        int totalRedact = currentMonthData.getRedactInteractions();
        int totalFeedback = currentMonthData.getFeedbackCount();

        StadisticsModel report = new StadisticsModel(totalAsk, totalRedact, totalFeedback, allComplaintFiles,
                allFeedbackFiles);

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
     * 
     * @param previousValue the previous week's value
     * @param currentValue  the current week's value
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

    // ======================
    // Prediction Methods
    // ======================

    /**
     * Generates an AI prediction based on yearly statistics data.
     * Calls OpenRouter to get insights and predictions for the next month.
     *
     * @param model  the statistics model with yearly data
     * @param cityId the city identifier
     * @return prediction text from AI, or fallback message on failure
     */
    @Override
    public String generatePrediction(StadisticsModel model, String cityId) {
        logger.info("Generating AI prediction for city: {}", cityId);

        try {
            ArrayList<MonthlyData> yearlyData = model.getYearlyData();
            if (yearlyData == null || yearlyData.isEmpty()) {
                logger.warn("No yearly data available for prediction");
                return FALLBACK_PREDICTION;
            }

            // Build the prompt with yearly data
            String prompt = buildPredictionPrompt(yearlyData, cityId);

            OpenRouterResponseDto response = openRouterService.ask(prompt, null, cityId);

            if (response.isSuccess() && response.getMessage() != null && !response.getMessage().isBlank()) {
                logger.info("AI prediction generated successfully for city: {}", cityId);
                return response.getMessage();
            } else {
                logger.warn("AI prediction call returned empty or failed for city: {}", cityId);
                return FALLBACK_PREDICTION;
            }
        } catch (Exception e) {
            logger.error("Failed to generate AI prediction for city {}: {}", cityId, e.getMessage());
            return FALLBACK_PREDICTION;
        }
    }

    private String buildPredictionPrompt(ArrayList<MonthlyData> yearlyData, String cityId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analitza les següents dades estadístiques any ")
                .append(YearMonth.now(ZoneId.of("Europe/Madrid")).getYear()).append(" per a la ciutat ").append(cityId)
                .append(":\n\n");

        sb.append("| Mes | Consultes | Reclamacions | Valoracions | Arxius Reclam. | Arxius Valoracions |\n");
        sb.append("|---|---|---|---|---|---|\n");

        int totalAsk = 0, totalRedact = 0, totalFeedback = 0, totalComplaintFiles = 0, totalFeedbackFiles = 0;

        for (MonthlyData md : yearlyData) {
            int complaintFiles = md.getComplaintFiles() != null ? md.getComplaintFiles().size() : 0;
            int feedbackFiles = md.getFeedbackFiles() != null ? md.getFeedbackFiles().size() : 0;

            sb.append("| ").append(md.getMonthLabel()).append(" | ")
                    .append(md.getAskInteractions()).append(" | ")
                    .append(md.getRedactInteractions()).append(" | ")
                    .append(md.getFeedbackCount()).append(" | ")
                    .append(complaintFiles).append(" | ")
                    .append(feedbackFiles).append(" |\n");

            totalAsk += md.getAskInteractions();
            totalRedact += md.getRedactInteractions();
            totalFeedback += md.getFeedbackCount();
            totalComplaintFiles += complaintFiles;
            totalFeedbackFiles += feedbackFiles;
        }

        sb.append("\n**Total anual:** ").append(totalAsk).append(" consultes, ")
                .append(totalRedact).append(" reclamacions, ")
                .append(totalFeedback).append(" valoracions.\n");
        sb.append("**Arxius generats:** ").append(totalComplaintFiles).append(" reclamacions, ")
                .append(totalFeedbackFiles).append(" valoracions.\n\n");

        sb.append("Basant-te en aquestes dades, proporciona:");
        sb.append("\n1. Una breu anàlisi de les tendències (quin tipus d'interacció creix, quin disminueix)");
        sb.append("\n2. Una predicció per al proper mes (quines xifres esperes)");
        sb.append("\n3. Recomanacions per millorar el servei ciutadà");
        sb.append("\n\nRespon en català i en format HTML senzill (paràgrafs i llistes).");

        return sb.toString();
    }

    private static final String FALLBACK_PREDICTION = "<p style=\"margin:0;font-size:12px;color:#9CA3AF;font-style:italic;\">No s'ha pogut generar la predicció basada en les dades actuals.</p>";

}
