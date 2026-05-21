package cat.complai.config;

import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Configuration bean that scans JVM environment variables for Telegram bot
 * tokens and webhook secrets per municipality.
 *
 * <p>At startup it reads all environment variables looking for the prefixes:
 * <ul>
 *   <li>{@code TOKEN_TELEGRAM_<CITYID>} → bot token for a city</li>
 *   <li>{@code TELEGRAM_WEBHOOK_SECRET_<CITYID>} → webhook secret for a city</li>
 * </ul>
 * The city identifier is the lowercased suffix after the prefix
 * (e.g. {@code TOKEN_TELEGRAM_ELPRAT} → {@code "elprat"}).
 *
 * <p>Bots are optional — if no {@code TOKEN_TELEGRAM_*} vars are found a
 * warning is logged but the bean is still created. Callers should use
 * {@link #hasBotForCity(String)} to check before calling
 * {@link #getToken(String)}.
 *
 * @see <a href="https://core.telegram.org/bots/api">Telegram Bot API</a>
 */
@Singleton
public class TelegramConfiguration {

    private static final Logger logger = Logger.getLogger(TelegramConfiguration.class.getName());

    private final Map<String, String> cityIdToToken;
    private final Map<String, String> cityIdToWebhookSecret;

    /**
     * Production constructor — scans {@link System#getenv()} at startup.
     */
    public TelegramConfiguration() {
        Map<String, String> tokens = new HashMap<>();
        Map<String, String> secrets = new HashMap<>();

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (key.startsWith("TOKEN_TELEGRAM_")) {
                String cityId = key.substring("TOKEN_TELEGRAM_".length()).toLowerCase();
                tokens.put(cityId, value);
            } else if (key.startsWith("TELEGRAM_WEBHOOK_SECRET_")) {
                String cityId = key.substring("TELEGRAM_WEBHOOK_SECRET_".length()).toLowerCase();
                secrets.put(cityId, value);
            }
        }

        this.cityIdToToken = Map.copyOf(tokens);
        this.cityIdToWebhookSecret = Map.copyOf(secrets);

        if (tokens.isEmpty()) {
            logger.warning("No TOKEN_TELEGRAM_* environment variables found. Telegram bot is disabled.");
        } else {
            logger.info("TelegramConfiguration initialized with " + tokens.size()
                    + " bot(s): " + String.join(", ", tokens.keySet()));
        }
    }

    /**
     * Test-only constructor — accepts pre-built maps instead of scanning
     * environment variables.
     *
     * @param tokens  map of cityId → bot token
     * @param secrets map of cityId → webhook secret
     */
    TelegramConfiguration(Map<String, String> tokens, Map<String, String> secrets) {
        this.cityIdToToken = Map.copyOf(tokens);
        this.cityIdToWebhookSecret = Map.copyOf(secrets);
    }

    /**
     * Returns the Telegram bot token for the given city.
     *
     * @param cityId the lowercased municipality identifier
     * @return the bot token (never blank)
     * @throws IllegalArgumentException if no token is configured for the city
     */
    public String getToken(String cityId) {
        String token = cityIdToToken.get(cityId);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("No Telegram bot token configured for city: " + cityId);
        }
        return token;
    }

    /**
     * Returns the Telegram webhook secret for the given city.
     *
     * @param cityId the lowercased municipality identifier
     * @return the secret, or an empty string if not configured
     */
    public String getWebhookSecret(String cityId) {
        return cityIdToWebhookSecret.getOrDefault(cityId, "");
    }

    /**
     * Returns {@code true} if a Telegram bot token is configured for the given
     * city.
     *
     * @param cityId the lowercased municipality identifier
     * @return {@code true} if a token exists
     */
    public boolean hasBotForCity(String cityId) {
        return cityIdToToken.containsKey(cityId);
    }

    /**
     * Returns an immutable set of all city identifiers that have a Telegram bot
     * configured.
     *
     * @return set of lowercased city IDs
     */
    public Set<String> getAllConfiguredCities() {
        return cityIdToToken.keySet();
    }
}
