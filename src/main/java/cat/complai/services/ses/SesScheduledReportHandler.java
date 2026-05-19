package cat.complai.services.ses;

import java.util.Map;
import java.util.logging.Logger;

import io.micronaut.function.aws.MicronautRequestHandler;
import jakarta.inject.Inject;

/**
 * AWS Lambda handler for scheduled statistics report emails via SES.
 *
 * <p>Runs every Monday at 03:00 (server time) via an EventBridge rule in production
 * and a CloudWatch Events rule in SAM. This thin handler delegates all business logic
 * to {@link MultiCitySesService}, which discovers configured cities and sends per-city reports.
 *
 * <p>Lambda configuration:
 * <ul>
 *   <li>Handler: cat.complai.services.ses.SesScheduledReportHandler::handleRequest</li>
 *   <li>Memory: 512 MB</li>
 *   <li>Timeout: 60 seconds</li>
 *   <li>Trigger: EventBridge / CloudWatch Events (cron(0 3 ? * MON *))</li>
 * </ul>
 *
 * <p>Local development: the handler is invoked via {@code sam local invoke} by
 * {@code ses_scheduled_poller.py}, which runs on a cron-like loop through SAM.
 */
public class SesScheduledReportHandler extends MicronautRequestHandler<Map<String, Object>, String> {

    private static final Logger logger = Logger.getLogger(SesScheduledReportHandler.class.getName());

    @Inject
    private MultiCitySesService multiCityService;

    @Override
    public String execute(Map<String, Object> event) {
        logger.info("SesScheduledReportHandler — scheduled invocation received");
        logger.info(() -> "Event source: " + event.get("source")
                + " | detail-type: " + event.get("detail-type"));
        return multiCityService.runReportsForAllCities();
    }
}