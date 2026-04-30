package cat.complai.services.ses.models;

import io.micronaut.core.annotation.Introspected;

@Introspected
public class StadisticsModel {

    private String topCategory;
    private int totalAskInteractions;
    private int totalFeedbacks;
    private int totalRedactInteractions;

    // Constructors
    public StadisticsModel() {
    }

    public StadisticsModel(String topCategory, int totalAskInteractions, int totalFeedbacks, int totalRedactInteractions) {
        this.topCategory = topCategory;
        this.totalAskInteractions = totalAskInteractions;
        this.totalFeedbacks = totalFeedbacks;
        this.totalRedactInteractions = totalRedactInteractions;
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

}
