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
        // Build HTML with labels and ul tags for files
        StringBuilder sb = new StringBuilder();
        sb.append("<p><strong>Stadistics Report:</strong></p>\n");
        sb.append("<p><strong>Total Ask logs:</strong> ").append(totalAskInteractions).append("</p>\n");
        sb.append("<p><strong>Total Feedback logs:</strong> ").append(totalFeedbacks).append("</p>\n");
        sb.append("<p><strong>Total Redact logs:</strong> ").append(totalRedactInteractions).append("</p>\n");
        sb.append("<p><strong>Complaint Files:</strong> ").append(complaintFile.size()).append("</p>\n");
        if (!complaintFile.isEmpty()) {
            sb.append("<ul>\n");
            for (ComplaintFile file : complaintFile) {
                String url = file.getUrl() != null ? file.getUrl().toString() : "";
                sb.append("  <li>").append(file.getFileName()).append(": <a href=\"").append(url)
                        .append("\">Click here</a></li>\n");
            }
            sb.append("</ul>\n");
        }
        sb.append("<p><strong>Feedback files:</strong> ").append(feedbackFile.size()).append("</p>\n");
        if (!feedbackFile.isEmpty()) {
            sb.append("<ul>\n");
            for (FeedbackFile file : feedbackFile) {
                String url = file.getUrl() != null ? file.getUrl().toString() : "";
                sb.append("  <li>").append(file.getFileName()).append(": <a href=\"").append(url)
                        .append("\">Click here</a></li>\n");
            }
            sb.append("</ul>\n");
        }
        return sb.toString();
    }
}
