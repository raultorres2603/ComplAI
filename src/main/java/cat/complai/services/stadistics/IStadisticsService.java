package cat.complai.services.stadistics;

import cat.complai.services.stadistics.models.StadisticsModel;
import cat.complai.exceptions.ses.CloudWatchLogsException;

public interface IStadisticsService {

    /**
     * Generates a statistics report for all cities combined.
     *
     * @return the statistics model with aggregated data from all cities
     * @throws CloudWatchLogsException if CloudWatch is unavailable
     */
    StadisticsModel generateStadisticsReport() throws CloudWatchLogsException;

    /**
     * Generates a city-specific statistics report.
     * Filters CloudWatch logs and S3 files by the specified city.
     *
     * @param cityId the city identifier (e.g., "elprat")
     * @return the statistics model with data filtered for the specified city
     * @throws CloudWatchLogsException if CloudWatch is unavailable
     */
    StadisticsModel generateStadisticsReport(String cityId) throws CloudWatchLogsException;

    /**
     * Generates an AI prediction based on yearly statistics data.
     *
     * @param model the statistics model with yearly data
     * @param cityId the city identifier
     * @return prediction text from AI, or fallback message on failure
     */
    String generatePrediction(StadisticsModel model, String cityId);

}
