package cat.complai.helpers.openrouter;

import io.micronaut.context.annotation.Value;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pre-warms the RAG indexes for the default city at application startup.
 *
 * <p>
 * Eagerly calls {@link ProcedureRagHelperRegistry#getForCity},
 * {@link EventRagHelperRegistry#getForCity}, and
 * {@link CityInfoRagHelperRegistry#getForCity}
 * for the city configured by {@code complai.default-city-id} (default:
 * {@code elprat}).
 * This amortises the first-request S3 I/O and index-build cost, reducing
 * perceived latency
 * on the first real user request after a Lambda cold start.
 *
 * <p>
 * Failures are swallowed so a warmup problem can never prevent the application
 * from
 * starting.
 */
@Singleton
public class RagWarmupService {

    private static final Logger logger = Logger.getLogger(RagWarmupService.class.getName());

    private final ProcedureRagHelperRegistry procedureRegistry;
    private final EventRagHelperRegistry eventRegistry;
    private final CityInfoRagHelperRegistry cityInfoRegistry;
    private final String defaultCityId;

    /**
     * Constructs the service with its registry dependencies and the default city
     * ID.
     *
     * @param procedureRegistry registry for procedure RAG helpers
     * @param eventRegistry     registry for event RAG helpers
     * @param cityInfoRegistry  registry for city-info RAG helpers
     * @param defaultCityId     identifier of the city to pre-warm; blank disables
     *                          warmup
     */
    public RagWarmupService(ProcedureRagHelperRegistry procedureRegistry,
            EventRagHelperRegistry eventRegistry,
            CityInfoRagHelperRegistry cityInfoRegistry,
            @Value("${complai.default-city-id:elprat}") String defaultCityId) {
        this.procedureRegistry = procedureRegistry;
        this.eventRegistry = eventRegistry;
        this.cityInfoRegistry = cityInfoRegistry;
        this.defaultCityId = defaultCityId;
    }

    /**
     * Pre-warms the RAG indexes for the default city immediately after the bean is
     * constructed.
     */
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
