package cat.complai.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Amazon SES (Simple Email Service) configuration bean.
 * 
 * Properties are bound from application.properties with the prefix "aws.ses".
 * All configuration values are injected from environment variables at runtime.
 * 
 * Example application.properties:
 * {@code
 * aws.ses.from-email=${AWS_SES_FROM_EMAIL:}
 * aws.ses.region=${AWS_SES_REGION:eu-west-1}
 * }
 * 
 * Environment Variables (GitHub Actions):
 * - AWS_SES_FROM_EMAIL: The verified sender email address in AWS SES
 * Required for all environments (dev, staging, prod)
 * Example: noreply@elprathq.cat
 * - AWS_SES_REGION: AWS region for SES service
 * Default: eu-west-1
 * Example: eu-west-1, us-east-1
 * 
 * @author ComplAI Team
 * @version 1.0
 */
@Introspected
@ConfigurationProperties("aws.ses")
public class SesConfiguration {

    /**
     * The verified sender email address in Amazon SES.
     * Must be a valid email address verified in the AWS SES console.
     * 
     * Injected from: AWS_SES_FROM_EMAIL environment variable
     * Required: YES
     */
    @NotBlank(message = "aws.ses.from-email is required. Set AWS_SES_FROM_EMAIL environment variable.")
    @Email(message = "aws.ses.from-email must be a valid email address")
    private String fromEmail;

    /**
     * AWS region where SES service is deployed.
     * Must be a valid AWS region supporting SES.
     * 
     * Injected from: AWS_SES_REGION environment variable
     * Default: eu-west-1
     * Common regions: eu-west-1, us-east-1, ap-southeast-1
     */
    private String region = "eu-west-1";

    /**
     * The recipient email address for statistics reports.
     * 
     * Injected from: AWS_SES_RECIPIENT_EMAIL environment variable
     * Required: YES
     */
    @NotBlank(message = "aws.ses.recipient-email is required. Set AWS_SES_RECIPIENT_EMAIL environment variable.")
    @Email(message = "aws.ses.recipient-email must be a valid email address")
    private String recipientEmail;

    /**
     * Constructs an empty SesConfiguration.
     * Properties are set via dependency injection from application.properties.
     */
    public SesConfiguration() {
    }

    /**
     * Constructs a SesConfiguration with explicit values.
     * Used for testing or manual instantiation.
     *
     * @param fromEmail The verified sender email address
     * @param region    The AWS region for SES
     * @param recipientEmail The recipient email address for reports
     */
    public SesConfiguration(String fromEmail, String region, String recipientEmail) {
        this.fromEmail = fromEmail;
        this.region = region;
        this.recipientEmail = recipientEmail;
    }

    /**
     * Gets the verified sender email address.
     *
     * @return The email address to use as the "From" address in SES requests
     */
    public String getFromEmail() {
        return fromEmail;
    }

    /**
     * Sets the verified sender email address.
     *
     * @param fromEmail The email address to use as the "From" address in SES
     *                  requests
     */
    public void setFromEmail(String fromEmail) {
        this.fromEmail = fromEmail;
    }

    /**
     * Gets the AWS region for SES.
     *
     * @return The AWS region identifier (e.g., eu-west-1)
     */
    public String getRegion() {
        return region;
    }

    /**
     * Sets the AWS region for SES.
     *
     * @param region The AWS region identifier
     */
    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * Gets the recipient email address for statistics reports.
     *
     * @return The email address to receive statistics reports
     */
    public String getRecipientEmail() {
        return recipientEmail;
    }

    /**
     * Sets the recipient email address for statistics reports.
     *
     * @param recipientEmail The email address to receive statistics reports
     */
    public void setRecipientEmail(String recipientEmail) {
        this.recipientEmail = recipientEmail;
    }

    @Override
    public String toString() {
        return "SesConfiguration{" +
                "fromEmail='" + maskEmail(fromEmail) + '\'' +
                ", region='" + region + '\'' +                ", recipientEmail='" + maskEmail(recipientEmail) + "'" +                '}';
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
