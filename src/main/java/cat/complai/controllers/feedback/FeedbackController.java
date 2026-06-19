package cat.complai.controllers.feedback;

import cat.complai.controllers.feedback.dto.FeedbackAcceptedDto;
import cat.complai.controllers.feedback.dto.FeedbackErrorDto;
import cat.complai.controllers.feedback.dto.FeedbackRequest;
import cat.complai.dto.feedback.FeedbackErrorCode;
import cat.complai.dto.feedback.FeedbackResult;
import cat.complai.services.feedback.FeedbackPublisherService;
import cat.complai.utilities.auth.ApiKeyAuthFilter;
import cat.complai.utilities.metrics.InteractionMetricsPublisher;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import jakarta.inject.Inject;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * REST Controller for the user feedback endpoint.
 *
 * <p>POST /complai/feedback accepts feedback submissions and returns HTTP 202 Accepted
 * immediately after publishing to SQS. The feedback is processed asynchronously by the
 * worker Lambda, which uploads JSON to S3.
 *
 * <p>Security:
 * <ul>
 *   <li>API key authentication is enforced by ApiKeyAuthFilter before the controller is reached</li>
 *   <li>City context is extracted from the API key and stored in request attributes</li>
 *   <li>No OIDC identity validation is required; users self-identify via userName and idUser</li>
 * </ul>
 */
@Controller("/complai")
public class FeedbackController {

    private final FeedbackPublisherService publisherService;
    private final InteractionMetricsPublisher metricsPublisher;
    private final Logger logger = Logger.getLogger(FeedbackController.class.getName());

    @Inject
    public FeedbackController(FeedbackPublisherService publisherService,
                              InteractionMetricsPublisher metricsPublisher) {
        this.publisherService = publisherService;
        this.metricsPublisher = metricsPublisher;
    }

    /**
     * Submits user feedback for async processing.
     *
     * <p>Request body:
     * <pre>
     * {
     *   "userName": "Joan Garcia",
     *   "idUser": "12345678A",
     *   "message": "The noise from the airport is unbearable..."
     * }
     * </pre>
     *
     * <p>Responses:
     * <ul>
     *   <li>202 Accepted: Feedback queued successfully</li>
     *   <li>400 Bad Request: Missing or invalid fields (userName, idUser, message)</li>
     *   <li>401 Unauthorized: Missing or invalid API key</li>
     *   <li>500 Internal Server Error: SQS publish failed</li>
     * </ul>
     *
     * @param request the feedback request body
     * @param httpRequest the HTTP request (to extract city from API key)
     * @return HTTP response with appropriate status and body
     */
    @Post("/feedback")
    public HttpResponse<?> feedback(@Body FeedbackRequest request, HttpRequest<?> httpRequest) {
        // Extract city from the API key claim (set by ApiKeyAuthFilter)
        String city = httpRequest.getAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, String.class)
                .orElseThrow(() -> new IllegalStateException(
                        "city attribute missing from request — API key filter should have set it"));

        long startTime = System.currentTimeMillis();
        logger.info(() -> "POST /complai/feedback received — city=" + city);

        try {
            // Call the service — it validates and publishes to SQS
            FeedbackResult result = publisherService.publishFeedback(request, city);

            // Pattern match on the result type

            return switch (result) {
                case FeedbackResult.Success success -> {
                    long latency = System.currentTimeMillis() - startTime;
                    FeedbackAcceptedDto data = success.data();
                    metricsPublisher.publishInteraction("FEEDBACK", city,
                                    true, latency);
                    logger.info(() -> "POST /complai/feedback — httpStatus=202 feedbackId="
                            + data.feedbackId() + " latencyMs=" + latency + " city=" + city);
                    yield HttpResponse.status(HttpStatus.ACCEPTED).body(data).contentType(MediaType.APPLICATION_JSON);
                }
                case FeedbackResult.Error error -> {
                    long latency = System.currentTimeMillis() - startTime;
                    FeedbackErrorCode code = error.errorCode();
                    int httpStatus = code == FeedbackErrorCode.VALIDATION ? 400 : 500;
                    metricsPublisher.publishInteraction("FEEDBACK", city,
                                    false, latency);
                    logger.info(() -> "POST /complai/feedback — httpStatus=" + httpStatus
                            + " errorCode=" + code.getCode() + " latencyMs=" + latency + " city=" + city);

                    var errorResponse = new FeedbackErrorDto(
                            false, code.getCode(), error.message());

                    if (code == FeedbackErrorCode.VALIDATION) {
                        yield HttpResponse.badRequest(errorResponse);
                    } else {
                        yield HttpResponse.serverError(errorResponse);
                    }
                }
            };

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            metricsPublisher.publishInteraction("FEEDBACK", city,
                            false, latency);
            logger.log(Level.SEVERE, "POST /complai/feedback failed — httpStatus=500"
                    + " latencyMs=" + latency + " city=" + city, e);

            var errorResponse = new FeedbackErrorDto(
                    false, FeedbackErrorCode.INTERNAL.getCode(),
                    "Internal server error: " + e.getMessage());

            return HttpResponse.serverError(errorResponse);
        }
    }
}