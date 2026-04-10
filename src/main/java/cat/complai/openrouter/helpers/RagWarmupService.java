package cat.complai.openrouter.helpers;

import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Startup service that pre-warms the in-memory RAG indexes for the default city.
 *
 * <p>Runs automatically after bean initialisation ({@code @PostConstruct}). Loading the
 * indexes during startup — rather than lazily on the first request — eliminates the
 * latency spike that would otherwise be paid by the first citizen to send a message after
 * a Lambda cold start.
 *
 * <p>A warmup failure is logged as a warning but does not prevent the application from
 * starting. The first request for the affected city will pay the loading cost instead.
 *
 * <p>The default city is controlled by the {@code complai.default-city-id} property
 * (mapped from the {@code COMPLAI_DEFAULT_CITY_ID} environment variable, default
 * {@code elprat}).
 */
@Singleton
public class RagWarmupService {

    private static final Logger logger = Logger.getLogger(RagWarmupService.class.getName());

    private final ProcedureRagHelperRegistry procedureRegistry;
    private final EventRagHelperRegistry eventRegistry;
    private final CityInfoRagHelperRegistry cityInfoRegistry;
    private final String defaultCityId;

    public RagWarmupService(ProcedureRagHelperRegistry procedureRegistry,
            EventRagHelperRegistry eventRegistry,
            CityInfoRagHelperRegistry cityInfoRegistry,
            @Value("${complai.default-city-id:elprat}") String defaultCityId) {
        this.procedureRegistry = procedureRegistry;
        this.eventRegistry = eventRegistry;
        this.cityInfoRegistry = cityInfoRegistry;
        this.defaultCityId = defaultCityId;
    }

    @PostConstruct
    public void onStartup() {
        if (defaultCityId == null || defaultCityId.isBlank()) {
            logger.info("RagWarmupService — complai.default-city-id is blank; skipping pre-warm");
            return;
        }
        logger.info("RagWarmupService — pre-warming RAG indexes for city=" + defaultCityId);
        long start = System.currentTimeMillis();
        try {
            procedureRegistry.getForCity(defaultCityId);
            eventRegistry.getForCity(defaultCityId);
            cityInfoRegistry.getForCity(defaultCityId);
            logger.info("RagWarmupService — pre-warm complete for city=" + defaultCityId
                    + " latencyMs=" + (System.currentTimeMillis() - start));
        } catch (Exception e) {
            // Log and swallow — a warmup failure must never prevent startup.
            logger.log(Level.WARNING,
                    "RagWarmupService — pre-warm failed for city=" + defaultCityId
                            + "; first request will pay the cost: " + e.getMessage(),
                    e);
        }
    }
}
