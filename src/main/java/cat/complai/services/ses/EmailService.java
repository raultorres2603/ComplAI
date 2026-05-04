package cat.complai.services.ses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cat.complai.config.ISesSenderConfig;
import cat.complai.exceptions.ses.CloudWatchLogsException;
import cat.complai.exceptions.ses.SesEmailException;
import cat.complai.services.stadistics.StadisticsService;
import cat.complai.services.stadistics.models.StadisticsModel;
import io.micronaut.context.annotation.Bean;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.ses.model.MessageRejectedException;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

/**
 * Email service for sending notifications via Amazon SES (Simple Email
 * Service).
 *
 * This service handles:
 * - Sending statistics reports to administrators
 * - Sending complaint confirmations to users (future enhancement)
 * - Error handling and logging for all SES operations
 *
 * Configuration: Injected from SesSenderConfig (aws.ses.* properties)
 * Depends on verified sender email in AWS SES console
 *
 * @author ComplAI Team
 * @version 1.0
 */
@Bean
@Singleton
public class EmailService implements IEmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class.getName());
    private final String fromEmail;
    private final SesClient sesClient;

    @Inject
    private StadisticsService stadisticsService;

    /**
     * Constructs the EmailService with SES sender configuration.
     *
     * @param senderConfig The SesSenderConfig containing fromEmail and region
     * @throws IllegalArgumentException if fromEmail is blank or invalid
     * @throws IllegalStateException    if SES client cannot be initialized
     */
    @Inject
    public EmailService(ISesSenderConfig senderConfig) {
        if (senderConfig == null) {
            throw new IllegalArgumentException("SesSenderConfig bean is required");
        }

        this.fromEmail = senderConfig.getFromEmail();

        if (this.fromEmail == null || this.fromEmail.isBlank()) {
            throw new IllegalArgumentException(
                    "SES sender email is not configured. " +
                            "Set AWS_SES_FROM_EMAIL environment variable and ensure it's a valid email.");
        }

        String region = senderConfig.getRegion();
        if (region == null || region.isBlank()) {
            region = "eu-west-1";
        }

        try {
            this.sesClient = SesClient.builder()
                    .region(Region.of(region))
                    .build();
            logger.info("EmailService initialized with sender: {} in region: {}",
                    maskEmail(this.fromEmail), region);
        } catch (Exception e) {
            logger.error("Failed to initialize SES client: {}", e.getMessage());
            throw new IllegalStateException("Cannot initialize Amazon SES client", e);
        }
    }

    /**
     * Sends a statistics report email to the specified recipient.
     *
     * @param to      The recipient email address
     * @param subject The email subject line
     * @throws IllegalArgumentException if to or subject is invalid
     */
    @Override
    public void sendStadistics(String to, String subject) throws SesEmailException, CloudWatchLogsException {
        if (to == null || to.isBlank()) {
            logger.error("Recipient email address is required");
            throw new IllegalArgumentException("Recipient email address cannot be empty");
        }

        if (subject == null || subject.isBlank()) {
            logger.error("Email subject is required");
            throw new IllegalArgumentException("Email subject cannot be empty");
        }

        // Build the email body from the StadisticsModel
        StadisticsModel body = stadisticsService.generateStadisticsReport();

        // Construct the SES email request
        logger.info("Preparing to send statistics report to: {}", maskEmail(to));
        SendEmailRequest emailRequest = SendEmailRequest.builder()
                .destination(d -> d.toAddresses(to))
                .message(m -> m.subject(s -> s.data(subject))
                        .body(b -> b.text(t -> t.data(body.toString()))))
                .source(fromEmail)
                .build();

        try {
            SendEmailResponse response = sesClient.sendEmail(emailRequest);
            logger.info("Statistics report email sent successfully to: {}. Message ID: {}",
                    maskEmail(to), response.messageId());
        } catch (MessageRejectedException e) {
            logger.error("Email rejected by SES (address not verified or blacklisted): {}",
                    e.awsErrorDetails().errorMessage());
            throw new SesEmailException(
                    "SES rejected the email: " + e.awsErrorDetails().errorMessage(), e);
        } catch (MailFromDomainNotVerifiedException e) {
            logger.error("Sender domain is not verified in SES: {}", e.getMessage());
            throw new SesEmailException("Sender domain is not verified in SES", e);
        } catch (Exception e) {
            logger.error("Failed to send statistics report email: {}", e.getMessage(), e);
            throw new SesEmailException("Error sending email via SES: " + e.getMessage(), e);
        }
    }

    /**
     * Masks email for safe logging (hides most of local part).
     * 
     * @param email The email address to mask
     * @return Masked email (e.g., n***@example.com)
     */
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
