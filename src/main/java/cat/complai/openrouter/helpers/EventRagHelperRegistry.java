package cat.complai.openrouter.helpers;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Thread-safe cache of per-city {@link EventRagHelper} instances.
 *
 * <p>
 * Building a Lucene index is expensive (S3 I/O + index construction). Each
 * city's helper is
 * initialised at most once per warm Lambda instance and reused across all
 * subsequent requests.
 * Concurrent first-requests for the same city will race in
 * {@link ConcurrentHashMap#computeIfAbsent}
 * but only one winner will persist — the loser's result is discarded. This is
 * acceptable: the
 * overhead is bounded to Lambda cold-start contention, which is extremely rare.
 *
 * <p>
 * To support a new city, upload {@code events-<cityId>.json} to the S3 events
 * bucket.
 * The registry will initialise a helper for it on the first request that
 * carries that city in the JWT.
 */
@Singleton
public class EventRagHelperRegistry {

    private static final Logger logger = Logger.getLogger(EventRagHelperRegistry.class.getName());

    private final ConcurrentHashMap<String, EventRagHelper> helpersByCity = new ConcurrentHashMap<>();

    /**
     * Returns a cached {@link EventRagHelper} for the given city, building it
     * lazily on
     * first access. Subsequent calls for the same city return the same instance.
     *
     * @throws UncheckedIOException if the events file cannot be loaded for the
     *                              given city
     */
    public EventRagHelper getForCity(String cityId) {
        return helpersByCity.computeIfAbsent(cityId, this::buildHelper);
    }

    private EventRagHelper buildHelper(String cityId) {
        long startTime = System.currentTimeMillis();
        try {
            EventRagHelper helper = new EventRagHelper(cityId);
            long latency = System.currentTimeMillis() - startTime;
            int eventCount = helper.getAllEvents().size();
            logger.info(() -> "RAG INDEX BUILD — helper=EventRagHelper cityId=" + cityId
                    + " latencyMs=" + latency + " events=" + eventCount);
            return helper;
        } catch (IOException e) {
            long latency = System.currentTimeMillis() - startTime;
            logger.warning("RAG INDEX BUILD FAILED — helper=EventRagHelper cityId=" + cityId
                    + " latencyMs=" + latency + " error=" + e.getMessage());
            throw new UncheckedIOException(
                    "Failed to initialise EventRagHelper for city='" + cityId + "': " + e.getMessage(), e);
        }
    }
}
