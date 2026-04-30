package cat.complai.services.ses.models;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class StadisticsModel {

    private String topCategory;
    private int totalAskInteractions;
    private int totalFeedbacks;
    private int totalRedactInteractions;

    public StadisticsModel() {
        this.topCategory = topCategory();
        this.totalAskInteractions = totalAskInteractions();
        this.totalFeedbacks = totalFeedbacks();
        this.totalRedactInteractions = totalRedactInteractions();
    }

    private int totalRedactInteractions() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'totalRedactInteractions'");
    }

    private int totalFeedbacks() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'totalFeedbacks'");
    }

    private int totalAskInteractions() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'totalAskInteractions'");
    }

    private String topCategory() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'topCategory'");
    }

    // Getters and Setters
    public String getTopCategory() {
        return topCategory;
    }

    public void setTopCategory(String topCategory) {
        this.topCategory = topCategory;
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
        sb.append("Top Category: ").append(topCategory).append("\n");
        sb.append("Total Ask Interactions: ").append(totalAskInteractions).append("\n");
        sb.append("Total Feedbacks: ").append(totalFeedbacks).append("\n");
        sb.append("Total Redact Interactions: ").append(totalRedactInteractions).append("\n");
        return sb.toString();
    }
}
