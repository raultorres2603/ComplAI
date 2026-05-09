package cat.complai.services.stadistics.models;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class StadisticsModel {

    private int totalAskInteractions;
    private int totalFeedbacks;
    private int totalRedactInteractions;
    private ArrayList<ComplaintFile> complaintFile;
    private ArrayList<FeedbackFile> feedbackFile;

    // Monthly comparison fields (current month vs previous month)
    private MonthlyData currentMonth;
    private MonthlyData previousMonth;
    private ComparisonData askComparison;
    private ComparisonData redactComparison;
    private ComparisonData feedbackComparison;

    // Year-to-date monthly data (all months from Jan to current month)
    private ArrayList<MonthlyData> yearlyData;

    /**
     * Inner class to hold data for a single month period.
     */
    @Introspected
    public static class MonthlyData {
        private String monthLabel;  // e.g., "January 2026", "February 2026"
        private int askInteractions;
        private int redactInteractions;
        private int feedbackCount;
        private ArrayList<ComplaintFile> complaintFiles;
        private ArrayList<FeedbackFile> feedbackFiles;

        public MonthlyData() {}

        public MonthlyData(String monthLabel, int askInteractions, int redactInteractions, int feedbackCount,
                ArrayList<ComplaintFile> complaintFiles, ArrayList<FeedbackFile> feedbackFiles) {
            this.monthLabel = monthLabel;
            this.askInteractions = askInteractions;
            this.redactInteractions = redactInteractions;
            this.feedbackCount = feedbackCount;
            this.complaintFiles = complaintFiles;
            this.feedbackFiles = feedbackFiles;
        }

        public String getMonthLabel() { return monthLabel; }
        public void setMonthLabel(String monthLabel) { this.monthLabel = monthLabel; }
        public int getAskInteractions() { return askInteractions; }
        public void setAskInteractions(int askInteractions) { this.askInteractions = askInteractions; }
        public int getRedactInteractions() { return redactInteractions; }
        public void setRedactInteractions(int redactInteractions) { this.redactInteractions = redactInteractions; }
        public int getFeedbackCount() { return feedbackCount; }
        public void setFeedbackCount(int feedbackCount) { this.feedbackCount = feedbackCount; }
        public ArrayList<ComplaintFile> getComplaintFiles() { return complaintFiles; }
        public void setComplaintFiles(ArrayList<ComplaintFile> complaintFiles) { this.complaintFiles = complaintFiles; }
        public ArrayList<FeedbackFile> getFeedbackFiles() { return feedbackFiles; }
        public void setFeedbackFiles(ArrayList<FeedbackFile> feedbackFiles) { this.feedbackFiles = feedbackFiles; }

        /**
         * Creates a MonthlyData from a YearMonth.
         */
        public static MonthlyData fromYearMonth(YearMonth yearMonth) {
            String label = yearMonth.atDay(1).format(
                java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", new java.util.Locale("ca", "ES"))
            );
            // Capitalize first letter
            label = label.substring(0, 1).toUpperCase() + label.substring(1);
            return new MonthlyData(label, 0, 0, 0, new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * Inner class to hold comparison metrics (absolute difference + percentage change).
     */
    @Introspected
    public static class ComparisonData {
        private int absoluteDifference;
        private double percentageChange;

        public ComparisonData() {}

        public ComparisonData(int absoluteDifference, double percentageChange) {
            this.absoluteDifference = absoluteDifference;
            this.percentageChange = percentageChange;
        }

        public int getAbsoluteDifference() { return absoluteDifference; }
        public void setAbsoluteDifference(int absoluteDifference) { this.absoluteDifference = absoluteDifference; }
        public double getPercentageChange() { return percentageChange; }
        public void setPercentageChange(double percentageChange) { this.percentageChange = percentageChange; }
    }

    public StadisticsModel(int totalAskInteractions, int totalRedactInteractions, int totalFeedbacks,
            ArrayList<ComplaintFile> complaintFile, ArrayList<FeedbackFile> feedbackFile) {
        this.totalAskInteractions = totalAskInteractions;
        this.totalFeedbacks = totalFeedbacks;
        this.totalRedactInteractions = totalRedactInteractions;
        this.complaintFile = complaintFile;
        this.feedbackFile = feedbackFile;
    }

    /**
     * Backward-compatible constructor for tests and existing code.
     * Defaults complaintFile and feedbackFile to empty lists.
     */
    public StadisticsModel(int totalAskInteractions, int totalRedactInteractions, int totalFeedbacks) {
        this(totalAskInteractions, totalRedactInteractions, totalFeedbacks, new ArrayList<>(), new ArrayList<>());
    }

    public ArrayList<ComplaintFile> getComplaintFile() {
        return complaintFile;
    }

    public void setComplaintFile(ArrayList<ComplaintFile> complaintFile) {
        this.complaintFile = complaintFile;
    }

    public ArrayList<FeedbackFile> getFeedbackFile() {
        return feedbackFile;
    }

    public void setFeedbackFile(ArrayList<FeedbackFile> feedbackFile) {
        this.feedbackFile = feedbackFile;
    }

    public int getTotalAskInteractions() {
        return totalAskInteractions;
    }

    public void setTotalAskInteractions(int totalAskInteractions) {
        this.totalAskInteractions = totalAskInteractions;
    }

    public int getTotalFeedbacks() {
        return totalFeedbacks;
    }

    public void setTotalFeedbacks(int totalFeedbacks) {
        this.totalFeedbacks = totalFeedbacks;
    }

    public int getTotalRedactInteractions() {
        return totalRedactInteractions;
    }

    public void setTotalRedactInteractions(int totalRedactInteractions) {
        this.totalRedactInteractions = totalRedactInteractions;
    }

    // Getters and setters for monthly comparison fields
    public MonthlyData getCurrentMonth() { return currentMonth; }
    public void setCurrentMonth(MonthlyData currentMonth) { this.currentMonth = currentMonth; }
    public MonthlyData getPreviousMonth() { return previousMonth; }
    public void setPreviousMonth(MonthlyData previousMonth) { this.previousMonth = previousMonth; }
    public ComparisonData getAskComparison() { return askComparison; }
    public void setAskComparison(ComparisonData askComparison) { this.askComparison = askComparison; }
    public ComparisonData getRedactComparison() { return redactComparison; }
    public void setRedactComparison(ComparisonData redactComparison) { this.redactComparison = redactComparison; }
    public ComparisonData getFeedbackComparison() { return feedbackComparison; }
    public void setFeedbackComparison(ComparisonData feedbackComparison) { this.feedbackComparison = feedbackComparison; }
    public ArrayList<MonthlyData> getYearlyData() { return yearlyData; }
    public void setYearlyData(ArrayList<MonthlyData> yearlyData) { this.yearlyData = yearlyData; }

    @Override
    public String toString() {
        // Build HTML with labels and ul tags for files
        StringBuilder sb = new StringBuilder();
        sb.append("<p><strong>Stadistics Report:</strong></p>\n");

        // Legacy single-week data (backward compatible)
        sb.append("<p><strong>Total Ask logs:</strong> ").append(totalAskInteractions).append("</p>\n");
        sb.append("<p><strong>Total Feedback logs:</strong> ").append(totalFeedbacks).append("</p>\n");
        sb.append("<p><strong>Total Redact logs:</strong> ").append(totalRedactInteractions).append("</p>\n");
        sb.append("<p><strong>Complaint Files:</strong> ").append(complaintFile != null ? complaintFile.size() : 0).append("</p>\n");
        if (complaintFile != null && !complaintFile.isEmpty()) {
            sb.append("<ul>\n");
            for (ComplaintFile file : complaintFile) {
                String url = file.getUrl() != null ? file.getUrl().toString() : "";
                sb.append("  <li>").append(file.getFileName()).append(": <a href=\"").append(url)
                        .append("\">Click here</a></li>\n");
            }
            sb.append("</ul>\n");
        }
        sb.append("<p><strong>Feedback files:</strong> ").append(feedbackFile != null ? feedbackFile.size() : 0).append("</p>\n");
        if (feedbackFile != null && !feedbackFile.isEmpty()) {
            sb.append("<ul>\n");
            for (FeedbackFile file : feedbackFile) {
                String url = file.getUrl() != null ? file.getUrl().toString() : "";
                sb.append("  <li>").append(file.getFileName()).append(": <a href=\"").append(url)
                        .append("\">Click here</a></li>\n");
            }
            sb.append("</ul>\n");
        }

        // Year-to-date monthly data
        if (yearlyData != null && !yearlyData.isEmpty()) {
            // Determine year from current month label
            int currentYear = java.time.YearMonth.now(java.time.ZoneId.of("Europe/Madrid")).getYear();

            sb.append("<p><strong>--- Year-to-Date Monthly Data (").append(currentYear).append(") ---</strong></p>\n");

            // Table header
            sb.append("<table border='1' cellpadding='5' cellspacing='0' style='border-collapse: collapse;'>\n");
            sb.append("<tr><th>Month</th><th>Ask</th><th>Redact</th><th>Feedback</th></tr>\n");

            for (MonthlyData monthData : yearlyData) {
                sb.append("<tr>");
                sb.append("<td><strong>").append(monthData.getMonthLabel()).append("</strong></td>");
                sb.append("<td>").append(monthData.getAskInteractions()).append("</td>");
                sb.append("<td>").append(monthData.getRedactInteractions()).append("</td>");
                sb.append("<td>").append(monthData.getFeedbackCount()).append("</td>");
                sb.append("</tr>\n");
            }
            sb.append("</table>\n");

            // Current month vs Previous month comparison
            if (currentMonth != null) {
                sb.append("<p><strong>Current Month (").append(currentMonth.getMonthLabel()).append("):</strong></p>\n");
                sb.append("<ul>\n");
                sb.append("  <li>Ask interactions: ").append(currentMonth.getAskInteractions()).append("</li>\n");
                sb.append("  <li>Redact interactions: ").append(currentMonth.getRedactInteractions()).append("</li>\n");
                sb.append("  <li>Feedback count: ").append(currentMonth.getFeedbackCount()).append("</li>\n");
                sb.append("</ul>\n");
            }

            if (previousMonth != null) {
                sb.append("<p><strong>Previous Month (").append(previousMonth.getMonthLabel()).append("):</strong></p>\n");
                sb.append("<ul>\n");
                sb.append("  <li>Ask interactions: ").append(previousMonth.getAskInteractions()).append("</li>\n");
                sb.append("  <li>Redact interactions: ").append(previousMonth.getRedactInteractions()).append("</li>\n");
                sb.append("  <li>Feedback count: ").append(previousMonth.getFeedbackCount()).append("</li>\n");
                sb.append("</ul>\n");
            }

            // Comparison (current vs previous)
            if (askComparison != null || redactComparison != null || feedbackComparison != null) {
                sb.append("<p><strong>Comparison (");

                if (currentMonth != null && previousMonth != null) {
                    sb.append(previousMonth.getMonthLabel()).append(" → ").append(currentMonth.getMonthLabel());
                } else {
                    sb.append("Current vs Previous");
                }
                sb.append("):</strong></p>\n");

                if (askComparison != null) {
                    sb.append("<ul>\n");
                    sb.append("  <li>Ask: ").append(askComparison.getAbsoluteDifference() >= 0 ? "+" : "")
                       .append(askComparison.getAbsoluteDifference())
                       .append(" (").append(formatPercentage(askComparison.getPercentageChange())).append("%)</li>\n");
                    sb.append("</ul>\n");
                }
                if (redactComparison != null) {
                    sb.append("<ul>\n");
                    sb.append("  <li>Redact: ").append(redactComparison.getAbsoluteDifference() >= 0 ? "+" : "")
                       .append(redactComparison.getAbsoluteDifference())
                       .append(" (").append(formatPercentage(redactComparison.getPercentageChange())).append("%)</li>\n");
                    sb.append("</ul>\n");
                }
                if (feedbackComparison != null) {
                    sb.append("<ul>\n");
                    sb.append("  <li>Feedback: ").append(feedbackComparison.getAbsoluteDifference() >= 0 ? "+" : "")
                       .append(feedbackComparison.getAbsoluteDifference())
                       .append(" (").append(formatPercentage(feedbackComparison.getPercentageChange())).append("%)</li>\n");
                    sb.append("</ul>\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * Renders this model as a polished HTML email body using the given renderer.
     *
     * @param renderer        the HTML renderer (injected as a CDI bean)
     * @param reportGeneratedAt when the report was generated
     * @return complete HTML string ready for SES
     */
    public String renderHtml(cat.complai.services.stadistics.StadisticsHtmlRenderer renderer,
                             Instant reportGeneratedAt) {
        return renderer.render(this, reportGeneratedAt);
    }

    /**
     * Helper to format percentage for display.
     */
    private String formatPercentage(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "N/A";
        }
        return String.format("%.2f", value);
    }
}
