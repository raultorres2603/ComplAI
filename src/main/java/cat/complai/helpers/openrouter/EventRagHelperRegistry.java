package cat.complai.helpers.openrouter;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Thread-safe cache of per-city {@link RagHelper} instances for city events.
 *
 * <p>
 * Building the in-memory retrieval index is expensive (S3 I/O + index
 * construction). Each city's helper is initialised at most once per warm Lambda
 * instance and reused across all subsequent requests.
 *
 * <p>
 * To support a new city, upload {@code events-<cityId>.json} to the S3 events
 * bucket. The registry will initialise a helper for it on the first request
 * that carries that city in the JWT.
 */
@Singleton
public class EventRagHelperRegistry {

    private static final Logger logger = Logger.getLogger(EventRagHelperRegistry.class.getName());

    private final ConcurrentHashMap<String, RagHelper<RagHelper.Event>> helpersByCity = new ConcurrentHashMap<>();

    /**
     * Constructs the registry.
     */
    @Inject
    public EventRagHelperRegistry() {
    }

    /**
     * Returns a cached {@link RagHelper} for the given city's events, building it
     * lazily on first access. Subsequent calls for the same city return the same instance.
     *
     * @param cityId the city identifier
     * @return the event RAG helper
     */
    public RagHelper<RagHelper.Event> getForCity(String cityId) {
        return helpersByCity.computeIfAbsent(cityId, this::buildHelper);
    }

    private RagHelper<RagHelper.Event> buildHelper(String cityId) {
        long startTime = System.currentTimeMillis();
        RagHelper<RagHelper.Event> helper = RagHelper.forEvents(cityId);
        long latency = System.currentTimeMillis() - startTime;
        int eventCount = helper.getAll().size();
        logger.info(() -> "RAG INDEX BUILD — helper=EventRagHelper cityId=" + cityId
                + " latencyMs=" + latency + " events=" + eventCount);
        return helper;
    }
}
