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

    @Override
    public String toString() {
        // Build a block of text with the statistics
        StringBuilder sb = new StringBuilder();
        sb.append("Stadistics Report:\n");
        sb.append("Total Ask logs: ").append(totalAskInteractions).append("\n");
        sb.append("Total Feedback logs: ").append(totalFeedbacks).append("\n");
        sb.append("Total Redact logs: ").append(totalRedactInteractions).append("\n");
        sb.append("Complaint Files: ").append(complaintFile.size()).append("\n");
        // For each complaint file, add its name with a "Click here" link
        for (ComplaintFile file : complaintFile) {
            String url = file.getUrl() != null ? file.getUrl().toString() : "";
            sb.append("- ").append(file.getFileName()).append(": <a href=\"").append(url)
                    .append("\">Click here</a>\n");
        }
        sb.append("Feedback files: ").append(feedbackFile.size()).append("\n");
        // For each feedback file, add its name with a "Click here" link
        for (FeedbackFile file : feedbackFile) {
            String url = file.getUrl() != null ? file.getUrl().toString() : "";
            sb.append("- ").append(file.getFileName()).append(": <a href=\"").append(url)
                    .append("\">Click here</a>\n");
        }
        return sb.toString();
    }
}
