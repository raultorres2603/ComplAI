package cat.complai.services.stadistics.models;

import java.util.ArrayList;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class StadisticsModel {

    private int totalAskInteractions;
    private int totalFeedbacks;
    private int totalRedactInteractions;
    private ArrayList<ComplaintFile> complaintFile;

    public StadisticsModel(int totalAskInteractions, int totalRedactInteractions, int totalFeedbacks,
            ArrayList<ComplaintFile> complaintFile) {
        this.totalAskInteractions = totalAskInteractions;
        this.totalFeedbacks = totalFeedbacks;
        this.totalRedactInteractions = totalRedactInteractions;
        this.complaintFile = complaintFile;
    }

    /**
     * Backward-compatible constructor for tests and existing code.
     * Defaults complaintFile to an empty list.
     */
    public StadisticsModel(int totalAskInteractions, int totalRedactInteractions, int totalFeedbacks) {
        this(totalAskInteractions, totalRedactInteractions, totalFeedbacks, new ArrayList<>());
    }

    public ArrayList<ComplaintFile> getComplaintFile() {
        return complaintFile;
    }

    public void setComplaintFile(ArrayList<ComplaintFile> complaintFile) {
        this.complaintFile = complaintFile;
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
        sb.append("Total Ask Interactions: ").append(totalAskInteractions).append("\n");
        sb.append("Total Feedbacks: ").append(totalFeedbacks).append("\n");
        sb.append("Total Redact Interactions: ").append(totalRedactInteractions).append("\n");
        sb.append("Complaint Files: ").append(complaintFile.size()).append("\n");
        // For each complaint file, add its name and URL
        for (ComplaintFile file : complaintFile) {
            sb.append("- ").append(file.getFileName()).append(": ").append(file.getUrl()).append("\n");
        }
        return sb.toString();
    }
}
