package cat.complai.services.stadistics.models;

import java.util.ArrayList;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class StadisticsModel {

    private int totalAskInteractions;
    private int totalFeedbacks;
    private int totalRedactInteractions;
    private ArrayList<ComplaintFile> complaintFile;
    private ArrayList<FeedbackFile> feedbackFile;

    // Weekly comparison fields
    private WeeklyData currentWeek;
    private WeeklyData previousWeek;
    private ComparisonData askComparison;
    private ComparisonData redactComparison;
    private ComparisonData feedbackComparison;

    /**
     * Inner class to hold data for a single week period.
     */
    @Introspected
    public static class WeeklyData {
        private int askInteractions;
        private int redactInteractions;
        private int feedbackCount;
        private ArrayList<ComplaintFile> complaintFiles;
        private ArrayList<FeedbackFile> feedbackFiles;

        public WeeklyData() {}

        public WeeklyData(int askInteractions, int redactInteractions, int feedbackCount,
                ArrayList<ComplaintFile> complaintFiles, ArrayList<FeedbackFile> feedbackFiles) {
            this.askInteractions = askInteractions;
            this.redactInteractions = redactInteractions;
            this.feedbackCount = feedbackCount;
            this.complaintFiles = complaintFiles;
            this.feedbackFiles = feedbackFiles;
        }

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

    // Getters and setters for weekly comparison fields
    public WeeklyData getCurrentWeek() { return currentWeek; }
    public void setCurrentWeek(WeeklyData currentWeek) { this.currentWeek = currentWeek; }
    public WeeklyData getPreviousWeek() { return previousWeek; }
    public void setPreviousWeek(WeeklyData previousWeek) { this.previousWeek = previousWeek; }
    public ComparisonData getAskComparison() { return askComparison; }
    public void setAskComparison(ComparisonData askComparison) { this.askComparison = askComparison; }
    public ComparisonData getRedactComparison() { return redactComparison; }
    public void setRedactComparison(ComparisonData redactComparison) { this.redactComparison = redactComparison; }
    public ComparisonData getFeedbackComparison() { return feedbackComparison; }
    public void setFeedbackComparison(ComparisonData feedbackComparison) { this.feedbackComparison = feedbackComparison; }

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

        // Weekly comparison data
        if (currentWeek != null || previousWeek != null) {
            sb.append("<p><strong>--- Weekly Comparison ---</strong></p>\n");

            // Current week
            if (currentWeek != null) {
                sb.append("<p><strong>Current Week (Last 7 days):</strong></p>\n");
                sb.append("<ul>\n");
                sb.append("  <li>Ask interactions: ").append(currentWeek.getAskInteractions()).append("</li>\n");
                sb.append("  <li>Redact interactions: ").append(currentWeek.getRedactInteractions()).append("</li>\n");
                sb.append("  <li>Feedback count: ").append(currentWeek.getFeedbackCount()).append("</li>\n");
                sb.append("  <li>Complaint files: ").append(currentWeek.getComplaintFiles() != null ? currentWeek.getComplaintFiles().size() : 0).append("</li>\n");
                sb.append("  <li>Feedback files: ").append(currentWeek.getFeedbackFiles() != null ? currentWeek.getFeedbackFiles().size() : 0).append("</li>\n");
                sb.append("</ul>\n");
            }

            // Previous week
            if (previousWeek != null) {
                sb.append("<p><strong>Previous Week (Days 8-14):</strong></p>\n");
                sb.append("<ul>\n");
                sb.append("  <li>Ask interactions: ").append(previousWeek.getAskInteractions()).append("</li>\n");
                sb.append("  <li>Redact interactions: ").append(previousWeek.getRedactInteractions()).append("</li>\n");
                sb.append("  <li>Feedback count: ").append(previousWeek.getFeedbackCount()).append("</li>\n");
                sb.append("  <li>Complaint files: ").append(previousWeek.getComplaintFiles() != null ? previousWeek.getComplaintFiles().size() : 0).append("</li>\n");
                sb.append("  <li>Feedback files: ").append(previousWeek.getFeedbackFiles() != null ? previousWeek.getFeedbackFiles().size() : 0).append("</li>\n");
                sb.append("</ul>\n");
            }

            // Comparisons
            sb.append("<p><strong>Comparisons (Current vs Previous):</strong></p>\n");
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

        return sb.toString();
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
