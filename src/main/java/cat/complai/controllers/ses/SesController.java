package cat.complai.controllers.ses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cat.complai.config.ISesRecipientProvider;
import cat.complai.services.ses.IEmailService;
import cat.complai.exceptions.ses.SesEmailException;
import cat.complai.exceptions.ses.CloudWatchLogsException;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import jakarta.inject.Inject;
import io.micronaut.http.HttpStatus;

@Controller("/complai/ses")
public class SesController {

    private final IEmailService emailService;
    private final ISesRecipientProvider recipientProvider;
    private final static Logger logger = LoggerFactory.getLogger(SesController.class);

    @Inject
    public SesController(IEmailService emailService, ISesRecipientProvider recipientProvider) {
        this.emailService = emailService;
        this.recipientProvider = recipientProvider;
    }

    @Get("/stadistics")
    public HttpResponse<String> sendStadisticsReport() {
        logger.info("Received request to send statistics report via SES");
        try {
            emailService.sendStadistics(recipientProvider.getRecipientEmail(), "Usage Statistics Report");
            logger.info("Statistics report sent successfully via SES");
            return HttpResponse.ok("Statistics report sent successfully");
        } catch (CloudWatchLogsException e) {
            logger.error("CloudWatch Logs service unavailable while sending statistics report: {}", e.getMessage(), e);
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Filter service unavailable. Please try again later.");
        } catch (SesEmailException e) {
            logger.error("SES email service unavailable while sending statistics report: {}", e.getMessage(), e);
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Email service unavailable. Please try again later.");
        } catch (Exception e) {
            logger.error("Unexpected error sending statistics report via SES: {}", e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

}
