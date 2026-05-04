package cat.complai.config;

/**
 * Provider interface for SES recipient email address.
 * 
 * This interface allows the controller to receive the recipient email
 * without depending directly on SesConfiguration, avoiding circular
 * dependency issues in testing.
 * 
 * @author ComplAI Team
 * @version 1.0
 */
public interface ISesRecipientProvider {

    /**
     * Gets the recipient email address for statistics reports.
     *
     * @return The email address to receive statistics reports
     */
    String getRecipientEmail();
}