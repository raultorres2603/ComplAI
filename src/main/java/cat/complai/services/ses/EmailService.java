package cat.complai.services.ses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cat.complai.services.ses.models.StadisticsModel;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.MessageRejectedException;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

@Singleton
public class EmailService implements IEmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class.getName());
    private final String fromEmail;
    private final SesClient sesClient;

    @Inject
    public EmailService(
            @Value("${aws.ses.from-email:}") String fromEmail,
            @Value("${AWS_REGION:eu-west-1}") String region) {
        this.fromEmail = fromEmail;
        this.sesClient = SesClient.builder().region(Region.of(region)).build();

    }

    @Override
    public void sendStadistics(String to, String subject) {
        // Build the email body from the StadisticsModel
        StadisticsModel body = new StadisticsModel();
        // Starting sending mail
        logger.info("Starting to send email to: {}", to);
        SendEmailRequest emailRequest = SendEmailRequest.builder()
                .destination(d -> d.toAddresses(to))
                .message(m -> m.subject(s -> s.data(subject))
                        .body(b -> b.text(t -> t.data(body.toString()))))
                .source(fromEmail)
                .build();
        try {
            SendEmailResponse response = sesClient.sendEmail(emailRequest);
            logger.info("Email sent successfully to: {}. Message ID: {}", to, response.messageId());
        } catch (MessageRejectedException e) {
            logger.error("Email rejected by SES: {}", e.awsErrorDetails().errorMessage());
        } catch (Exception e) {
            logger.error("Failed to send email: {}", e.getMessage());
        }

    }

}
