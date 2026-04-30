package cat.complai.services.stadistics.models;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micronaut.core.annotation.Introspected;
import jakarta.inject.Inject;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;

@Introspected
public class StadisticsModel {

    @Inject
    private final static Logger logger = LoggerFactory.getLogger(StadisticsModel.class.getName());

    private int totalAskInteractions;
    private int totalFeedbacks;
    private int totalRedactInteractions;

    public StadisticsModel() {
        this.totalAskInteractions = 0;
        this.totalFeedbacks = 0;
        this.totalRedactInteractions = 0;
    }

    public int getTotalAskInteractions() {
        return totalAskInteractions;
    }

    public int getTotalFeedbacks() {
        return totalFeedbacks;
    }

    public int getTotalRedactInteractions() {
        return totalRedactInteractions;
    }

    @Override
    public String toString() {
        // Build a block of text with the statistics
        StringBuilder sb = new StringBuilder();
        sb.append("Stadistics Report:\n");
        sb.append("Total Ask Interactions: ").append(totalAskInteractions).append("\n");
        sb.append("Total Feedbacks: ").append(totalFeedbacks).append("\n");
        sb.append("Total Redact Interactions: ").append(totalRedactInteractions).append("\n");
        return sb.toString();
    }
}
