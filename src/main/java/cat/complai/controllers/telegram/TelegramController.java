package cat.complai.controllers.telegram;

import cat.complai.config.TelegramConfiguration;
import cat.complai.controllers.telegram.dto.TelegramUpdate;
import cat.complai.services.telegram.TelegramBotService;
import cat.complai.utilities.auth.CityUtil;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Status;
import jakarta.inject.Inject;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Receives Telegram Bot webhook callbacks.
 *
 * <p>Exposes {@code POST /telegram/webhook/{cityId}} which is registered as the
 * webhook URL with Telegram via {@code setWebhook}. The city ID in the path
 * identifies which bot token to use from {@code TOKEN_TELEGRAM_<cityId>} env vars.
 *
 * <p>This controller is excluded from the {@code X-Api-Key} authentication filter
 * ({@link cat.complai.utilities.auth.JwtSessionAuthFilter}) since Telegram cannot send
 * custom headers. Security is provided via the {@code X-Telegram-Bot-Api-Secret-Token}
 * header validation.
 */
@Controller("/telegram")
public class TelegramController {

    private final TelegramConfiguration telegramConfig;
    private final TelegramBotService botService;
    private final Predicate<String> cityEnabledChecker;
    private final Logger logger = Logger.getLogger(TelegramController.class.getName());

    @Inject
    public TelegramController(TelegramConfiguration telegramConfig,
                              TelegramBotService botService) {
        this(telegramConfig, botService, CityUtil::isCityEnabled);
    }

    // Visible for testing — accepts a custom city-enabled checker
    public TelegramController(TelegramConfiguration telegramConfig,
                              TelegramBotService botService,
                              Predicate<String> cityEnabledChecker) {
        this.telegramConfig = telegramConfig;
        this.botService = botService;
        this.cityEnabledChecker = cityEnabledChecker;
    }

    /**
     * Handles an incoming Telegram webhook update.
     *
     * <p>Verifies the webhook secret token from the
     * {@code X-Telegram-Bot-Api-Secret-Token} header, then delegates to
     * {@link TelegramBotService#processUpdate(TelegramUpdate, String)}.
     *
     * @param update  the deserialized Telegram update
     * @param cityId  the city identifier from the URL path
     * @param request the raw HTTP request (for header extraction)
     * @return 200 OK with empty body (Telegram expects this for successful delivery)
     */
    @Post("/webhook/{cityId}")
    @Status(HttpStatus.OK)
    public HttpResponse<?> webhook(@Body TelegramUpdate update,
                                   @PathVariable String cityId,
                                   HttpRequest<?> request) {
        // Validate cityId
        if (cityId == null || cityId.isBlank()) {
            logger.warning("Telegram webhook called with missing cityId");
            return HttpResponse.badRequest();
        }

        // Check if city is enabled via ENABLE_CITY_<CITYID> environment variable.
        // Defaults to disabled when the variable is absent.
        if (!cityEnabledChecker.test(cityId)) {
            logger.info(() -> "City disabled for Telegram — cityId=" + cityId);
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
        }

        // Verify webhook secret token if configured
        if (!verifyWebhookSecret(request, cityId)) {
            logger.warning(() -> "Telegram webhook secret mismatch — cityId=" + cityId);
            return HttpResponse.unauthorized();
        }

        logger.fine(() -> "Telegram webhook received — cityId=" + cityId
                + " updateId=" + (update != null ? update.updateId() : "null"));

        if (update != null) {
            botService.processUpdate(update, cityId);
        }

        // Telegram expects 200 OK with empty body for successful delivery
        return HttpResponse.ok();
    }

    /**
     * Verifies the {@code X-Telegram-Bot-Api-Secret-Token} header against the
     * configured webhook secret for the given city. If no secret is configured
     * for the city, the check is skipped.
     *
     * @param request the HTTP request
     * @param cityId  the city identifier
     * @return true if the webhook secret is valid or not configured
     */
    private boolean verifyWebhookSecret(HttpRequest<?> request, String cityId) {
        String configuredSecret = telegramConfig.getWebhookSecret(cityId);
        if (configuredSecret == null || configuredSecret.isBlank()) {
            // No secret configured — skip verification
            return true;
        }

        String receivedSecret = request.getHeaders().get("X-Telegram-Bot-Api-Secret-Token");
        return configuredSecret.equals(receivedSecret);
    }
}
