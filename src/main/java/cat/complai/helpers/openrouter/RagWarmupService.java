package cat.complai.helpers.openrouter;

import cat.complai.config.TelegramConfiguration;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pre-warms the RAG indexes for all configured cities at application startup.
 *
 * <p>
 * Eagerly calls {@link ProcedureRagHelperRegistry#getForCity},
 * {@link EventRagHelperRegistry#getForCity}, and
 * {@link CityInfoRagHelperRegistry#getForCity}
 * for every city that has a Telegram bot configured (via
 * {@code TOKEN_TELEGRAM_<CITYID>} env vars), plus the default city from
 * {@code complai.default-city-id}.
 *
 * <p>
 * This amortises the first-request S3 I/O and index-build cost across all
 * cities, reducing perceived latency on the first real user request after a
 * Lambda cold start — regardless of which city the user belongs to.
 *
 * <p>
 * Failures are swallowed so a warmup problem can never prevent the application
 * from starting.
 */
@Singleton
public class RagWarmupService {

    private static final Logger logger = Logger.getLogger(RagWarmupService.class.getName());

    private final ProcedureRagHelperRegistry procedureRegistry;
    private final EventRagHelperRegistry eventRegistry;
    private final CityInfoRagHelperRegistry cityInfoRegistry;
    private final String defaultCityId;
    private final TelegramConfiguration telegramConfig;

    /**
     * Constructs the service with its registry dependencies and configuration.
     *
     * @param procedureRegistry registry for procedure RAG helpers
     * @param eventRegistry     registry for event RAG helpers
     * @param cityInfoRegistry  registry for city-info RAG helpers
     * @param defaultCityId     fallback city to pre-warm; used if no bots are configured
     * @param telegramConfig    Telegram bot configuration (source of city IDs)
     */
    public RagWarmupService(ProcedureRagHelperRegistry procedureRegistry,
            EventRagHelperRegistry eventRegistry,
            CityInfoRagHelperRegistry cityInfoRegistry,
            @Value("${complai.default-city-id:elprat}") String defaultCityId,
            TelegramConfiguration telegramConfig) {
        this.procedureRegistry = procedureRegistry;
        this.eventRegistry = eventRegistry;
        this.cityInfoRegistry = cityInfoRegistry;
        this.defaultCityId = defaultCityId;
        this.telegramConfig = telegramConfig;
    }

    /**
     * Pre-warms the RAG indexes for all configured cities immediately after the bean
     * is constructed.
     */
    @PostConstruct
    public void onStartup() {
        Set<String> citiesToWarm = collectCitiesToWarm();
        if (citiesToWarm.isEmpty()) {
            logger.info("RagWarmupService — no cities configured; skipping pre-warm");
            return;
        }

        logger.info("RagWarmupService — pre-warming RAG indexes for " + citiesToWarm.size()
                + " city/cities: " + citiesToWarm);
        long start = System.currentTimeMillis();

        for (String cityId : citiesToWarm) {
            try {
                procedureRegistry.getForCity(cityId);
                eventRegistry.getForCity(cityId);
                cityInfoRegistry.getForCity(cityId);
            } catch (Exception e) {
                // Log and swallow — a warmup failure must never prevent startup.
                logger.log(Level.WARNING,
                        "RagWarmupService — pre-warm failed for city=" + cityId
                                + "; first request will pay the cost: " + e.getMessage(),
                        e);
            }
        }

        logger.info("RagWarmupService — pre-warm complete for " + citiesToWarm.size()
                + " city/cities, total latencyMs=" + (System.currentTimeMillis() - start));
    }

    /**
     * Collects the set of city IDs that should have their RAG indexes pre-warmed.
     * Includes all cities with a Telegram bot configured plus the default city.
     */
    private Set<String> collectCitiesToWarm() {
        Set<String> cities = new HashSet<>();

        // Add all cities that have a Telegram bot configured
        if (telegramConfig != null) {
            cities.addAll(telegramConfig.getAllConfiguredCities());
        }

        // Always include the default city as a fallback
        if (defaultCityId != null && !defaultCityId.isBlank()) {
            cities.add(defaultCityId);
        }

        return cities;
    }
}
