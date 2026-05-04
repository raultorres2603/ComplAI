package cat.complai.config;

/**
 * Provider interface for SES sender configuration.
 * 
 * This interface allows EmailService to receive sender configuration
 * without depending directly on SesConfiguration, enabling better
 * testability and loose coupling.
 * 
 * @author ComplAI Team
 * @version 1.0
 */
public interface ISesSenderConfig {

    /**
     * Gets the verified sender email address.
     *
     * @return The email address to use as the "From" address in SES requests
     */
    String getFromEmail();

    /**
     * Gets the AWS region for SES.
     *
     * @return The AWS region identifier (e.g., eu-west-1)
     */
    String getRegion();
}