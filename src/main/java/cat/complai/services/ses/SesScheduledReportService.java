package cat.complai.services.ses;

import java.util.logging.Level;
import java.util.logging.Logger;

import cat.complai.config.ISesRecipientProvider;
import cat.complai.exceptions.ses.CloudWatchLogsException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Business logic for sending the scheduled statistics report via SES.
 *
 * <p>
 * Extracted from {@link SesScheduledReportHandler} so it can be unit-tested
 * without starting a Micronaut application context (which would attempt to bind
 * an HTTP port and collide with any embedded server already running in the test
 * JVM). The handler delegates to this class in production; tests instantiate it
 * directly with mock / fake dependencies.
 *
 * @see SesScheduledReportHandler
 */
@Singleton
public class SesScheduledReportService {

    private static final Logger logger = Logger.getLogger(SesScheduledReportService.class.getName());

    private final IEmailService emailService;
    private final ISesRecipientProvider recipientProvider;

    @Inject
    public SesScheduledReportService(
            IEmailService emailService,
            ISesRecipientProvider recipientProvider) {
        this.emailService = emailService;
        this.recipientProvider = recipientProvider;
    }

    /**
     * Runs the scheduled statistics report: fetches the recipient address,
     * generates the report, and sends it via SES.
     *
     * @return a result string beginning with {@code "OK:"} on success or
     *         {@code "ERROR:"} when the recipient address is not configured
     * @throws CloudWatchLogsException if CloudWatch is unavailable (triggers Lambda
     *                                 retry)
     * @throws RuntimeException        wrapping any other failure
     */
    public String run() {
        logger.info("SesScheduledReportService — generating statistics report");

        String recipient = recipientProvider.getRecipientEmail();
        if (recipient == null || recipient.isBlank()) {
            logger.severe("No recipient email configured — cannot send statistics report");
            return "ERROR: No recipient email configured";
        }

        try {
            emailService.sendStadistics(recipient, "ComplAI — Monthly Usage Statistics Report");
            logger.info("Statistics report sent successfully");
            return "OK: Statistics report sent to " + maskEmail(recipient);
        } catch (CloudWatchLogsException e) {
            logger.log(Level.SEVERE,
                    "CloudWatch Logs unavailable — could not generate statistics: "
                            + e.getMessage(),
                    e);
            throw e; // let Lambda surface as a handled error, triggering retry
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to send statistics report: " + e.getMessage(), e);
            throw new RuntimeException("Statistics report failed", e);
        }
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