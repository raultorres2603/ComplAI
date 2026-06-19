package cat.complai.controllers.telegram;

import cat.complai.config.TelegramConfiguration;
import cat.complai.controllers.telegram.dto.TelegramCallbackQuery;
import cat.complai.controllers.telegram.dto.TelegramChat;
import cat.complai.controllers.telegram.dto.TelegramMessage;
import cat.complai.controllers.telegram.dto.TelegramUpdate;
import cat.complai.controllers.telegram.dto.TelegramUser;
import cat.complai.services.telegram.TelegramBotService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TelegramController}.
 *
 * <p>Follows the project pattern for controller tests:
 * <ul>
 *   <li>No {@code @MicronautTest} — controllers are instantiated directly with
 *       fake dependencies</li>
 *   <li>Fake classes are inner {@code static class} types that extend the
 *       production class or implement the required interface</li>
 *   <li>Micronaut HTTP request objects are built manually with
 *       {@link HttpRequest#POST(String, Object)} and headers are set directly</li>
 * </ul>
 */
public class TelegramControllerTest {

    // -------------------------------------------------------------------------
    // Fake dependencies
    // -------------------------------------------------------------------------

    /**
     * A {@link TelegramConfiguration} that returns known webhook secrets for
     * testing, instead of reading from environment variables.
     */
    static class FakeTelegramConfig extends TelegramConfiguration {
        private final Map<String, String> secrets;

        FakeTelegramConfig() {
            super(); // calls the public no-arg constructor (reads System.getenv())
            this.secrets = Map.of("elprat", "test-secret");
        }

        @Override
        public String getWebhookSecret(String cityId) {
            return secrets.getOrDefault(cityId, "");
        }
    }

    /**
     * A {@link TelegramBotService} that records received updates instead of
     * processing them. All constructor parameters are passed as {@code null}
     * since the overridden {@link #processUpdate(TelegramUpdate, String)} never
     * delegates to the parent.
     */
    static class FakeBotService extends TelegramBotService {
        final List<TelegramUpdate> receivedUpdates = new ArrayList<>();
        final List<String> receivedCities = new ArrayList<>();

        FakeBotService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void processUpdate(TelegramUpdate update, String cityId) {
            receivedUpdates.add(update);
            receivedCities.add(cityId);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static MutableHttpRequest<?> requestWithSecret(String secret) {
        MutableHttpRequest<?> req = HttpRequest.POST("/telegram/webhook/elprat", "");
        req.header("X-Telegram-Bot-Api-Secret-Token", secret);
        return req;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void webhook_validUpdate_returns200() {
        var config = new FakeTelegramConfig();
        var botService = new FakeBotService();
        var controller = new TelegramController(config, botService);

        var chat = new TelegramChat(12345L, "private", "Test");
        var user = new TelegramUser(67890L, false, "TestUser", "en");
        var message = new TelegramMessage(1, user, chat, 1000000L, "Hello bot");
        var update = new TelegramUpdate(42L, message, null);

        HttpResponse<?> response = controller.webhook(update, "elprat",
                requestWithSecret("test-secret"));

        assertEquals(200, response.getStatus().getCode());
        assertEquals(1, botService.receivedUpdates.size());
        assertSame(update, botService.receivedUpdates.get(0));
        assertEquals("elprat", botService.receivedCities.get(0));
    }

    @Test
    void webhook_missingCityId_returns400() {
        var config = new FakeTelegramConfig();
        var botService = new FakeBotService();
        var controller = new TelegramController(config, botService);

        var update = new TelegramUpdate(42L, null, null);

        HttpResponse<?> response = controller.webhook(update, "",
                requestWithSecret("test-secret"));

        assertEquals(400, response.getStatus().getCode());
        assertTrue(botService.receivedUpdates.isEmpty(),
                "Bot service must not be called when cityId is empty");
    }

    @Test
    void webhook_wrongSecret_returns401() {
        var config = new FakeTelegramConfig();
        var botService = new FakeBotService();
        var controller = new TelegramController(config, botService);

        var update = new TelegramUpdate(42L, null, null);

        HttpResponse<?> response = controller.webhook(update, "elprat",
                requestWithSecret("wrong-secret"));

        assertEquals(401, response.getStatus().getCode());
        assertTrue(botService.receivedUpdates.isEmpty(),
                "Bot service must not be called when webhook secret is wrong");
    }

    @Test
    void webhook_validSecret_passesVerification() {
        var config = new FakeTelegramConfig();
        var botService = new FakeBotService();
        var controller = new TelegramController(config, botService);

        var chat = new TelegramChat(12345L, "private", "Test");
        var user = new TelegramUser(67890L, false, "TestUser", "en");
        var callback = new TelegramCallbackQuery("cb-1", user, null, "mode_ask");
        var update = new TelegramUpdate(100L, null, callback);

        HttpResponse<?> response = controller.webhook(update, "elprat",
                requestWithSecret("test-secret"));

        assertEquals(200, response.getStatus().getCode());
        assertEquals(1, botService.receivedUpdates.size());
        assertSame(update, botService.receivedUpdates.get(0));
        assertEquals("elprat", botService.receivedCities.get(0));
    }
}
