package cat.complai.controllers.ses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cat.complai.services.ses.EmailService;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import jakarta.inject.Inject;

@Controller("/ses")
public class SesController {

    private final EmailService emailService;
    private final static Logger logger = LoggerFactory.getLogger(SesController.class);

    @Inject
    public SesController(EmailService emailService) {
        this.emailService = emailService;
    }

    @Get("/stadistics")
    public void sendStadisticsReport() {
        logger.info("Received request to send statistics report via SES");
        try {
            emailService.sendStadistics("raultorres2603@gmail.com", "Usage Statistics Report");
            logger.info("Statistics report sent successfully via SES");
        } catch (Exception e) {
            logger.error("Error sending statistics report via SES: {}", e.getMessage());
        }
    }

}
