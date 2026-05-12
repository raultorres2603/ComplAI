package cat.complai.services.ses;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cat.complai.services.stadistics.IStadisticsService;
import jakarta.inject.Singleton;

/**
 * Service that discovers configured cities and sends per-city statistics reports via SES.
 *
 * <p>Only cities that have BOTH:
 * <ul>
 *   <li>{@code API_KEY_<CITYID>} environment variable (authentication)</li>
 *   <li>{@code AWS_SES_TO_EMAIL_<CITYID>} environment variable (email destination)</li>
 * </ul>
 * will receive scheduled statistics reports.
 *
 * <p>This enables a multi-city deployment where each city receives its own
 * customized statistics report instead of a combined report for all cities.
 */
@Singleton
public class MultiCitySesService {

    private static final Logger logger = LoggerFactory.getLogger(MultiCitySesService.class.getName());

    private static final String API_KEY_PREFIX = "API_KEY_";
    private static final String SES_TO_EMAIL_PREFIX = "AWS_SES_TO_EMAIL_";

    private final IEmailService emailService;
    private final IStadisticsService stadisticsService;

    // Cached map of cityId -> recipient email
    private final Map<String, String> configuredCities;

    public MultiCitySesService(IEmailService emailService, IStadisticsService stadisticsService) {
        this.emailService = emailService;
        this.stadisticsService = stadisticsService;
        this.configuredCities = discoverConfiguredCities();
        logger.info("MultiCitySesService initialized with {} configured cities: {}",
                configuredCities.size(), configuredCities.keySet());
    }

    /**
     * Returns the list of city IDs that have both API_KEY and AWS_SES_TO_EMAIL configured.
     *
     * @return list of configured city IDs
     */
    public List<String> getConfiguredCities() {
        return List.copyOf(configuredCities.keySet());
    }

    /**
     * Gets the SES recipient email for a specific city.
     *
     * @param cityId the city identifier (e.g., "elprat")
     * @return the recipient email address, or null if not configured
     */
    public String getRecipientEmail(String cityId) {
        return configuredCities.get(cityId);
    }

    /**
     * Checks if a city is configured for receiving SES reports.
     *
     * @param cityId the city identifier
     * @return true if the city has both API_KEY and AWS_SES_TO_EMAIL configured
     */
    public boolean isCityConfigured(String cityId) {
        return configuredCities.containsKey(cityId);
    }

    /**
     * Runs the statistics report for a specific city and sends it via SES.
     *
     * @param cityId the city identifier
     * @return result string starting with "OK:" on success or "ERROR:" on failure
     */
    public String runReportForCity(String cityId) {
        logger.info("Running statistics report for city: {}", cityId);

        String recipientEmail = getRecipientEmail(cityId);
        if (recipientEmail == null || recipientEmail.isBlank()) {
            logger.warn("No recipient email configured for city: {} — skipping", cityId);
            return "ERROR: No recipient email for city " + cityId;
        }

        try {
            // Generate city-specific statistics report
            stadisticsService.generateStadisticsReport(cityId);

            // Send the report via SES with cityId for prediction
            String subject = String.format("ComplAI — Monthly Statistics Report (%s)", cityId);
            emailService.sendStadistics(recipientEmail, subject, cityId);

            logger.info("Statistics report sent successfully for city: {} to: ***", cityId);
            return "OK: Statistics report for " + cityId + " sent to " + maskEmail(recipientEmail);

        } catch (Exception e) {
            logger.error("Failed to generate/send statistics report for city {}: {}", cityId, e.getMessage(), e);
            return "ERROR: Failed for city " + cityId + " — " + e.getMessage();
        }
    }

    /**
     * Runs statistics reports for all configured cities.
     *
     * @return summary of results for all cities
     */
    public String runReportsForAllCities() {
        logger.info("Running statistics reports for all configured cities...");

        if (configuredCities.isEmpty()) {
            logger.warn("No cities configured for SES reporting — nothing to do");
            return "ERROR: No cities configured for SES reporting";
        }

        StringBuilder summary = new StringBuilder();
        int successCount = 0;
        int failureCount = 0;

        for (String cityId : configuredCities.keySet()) {
            String result = runReportForCity(cityId);
            if (result.startsWith("OK:")) {
                successCount++;
            } else {
                failureCount++;
            }
            summary.append(cityId).append(": ").append(result).append("\n");
        }

        String finalSummary = String.format("Completed: %d succeeded, %d failed out of %d cities\n%s",
                successCount, failureCount, configuredCities.size(), summary);

        logger.info("All statistics reports completed: {} succeeded, {} failed",
                successCount, failureCount);

        return finalSummary;
    }

    /**
     * Discovers cities that have both API_KEY and AWS_SES_TO_EMAIL configured.
     *
     * @return map of cityId -> recipient email
     */
    private Map<String, String> discoverConfiguredCities() {
        Map<String, String> apiKeyCities = new HashMap<>();
        Map<String, String> sesEmailCities = new HashMap<>();

        // Scan environment variables for API_KEY_* and AWS_SES_TO_EMAIL_*
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (key.startsWith(API_KEY_PREFIX) && !value.isBlank()) {
                // API_KEY_ELPRAT -> elprat
                String cityId = key.substring(API_KEY_PREFIX.length()).toLowerCase();
                apiKeyCities.put(cityId, value);
            } else if (key.startsWith(SES_TO_EMAIL_PREFIX) && !value.isBlank()) {
                // AWS_SES_TO_EMAIL_ELPRAT -> elprat
                String cityId = key.substring(SES_TO_EMAIL_PREFIX.length()).toLowerCase();
                sesEmailCities.put(cityId, value);
            }
        }

        // Find intersection: cities with both API_KEY and AWS_SES_TO_EMAIL
        Set<String> commonCities = apiKeyCities.keySet().stream()
                .filter(sesEmailCities::containsKey)
                .collect(Collectors.toSet());

        Map<String, String> result = new HashMap<>();
        for (String cityId : commonCities) {
            result.put(cityId, sesEmailCities.get(cityId));
        }

        return result;
    }

    private static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int atIndex = email.indexOf("@");
        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        if (localPart.length() <= 1) {
            return "*" + domain;
        }
        return localPart.charAt(0) + "*".repeat(Math.max(1, localPart.length() - 2)) + domain;
    }
}