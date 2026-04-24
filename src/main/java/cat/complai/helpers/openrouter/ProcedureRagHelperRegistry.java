package cat.complai.helpers.openrouter;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Thread-safe cache of per-city {@link ProcedureRagHelper} instances.
 *
 * <p>
 * Building the in-memory retrieval index is expensive (S3 I/O + index construction). Each
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
 * To support a new city, upload {@code procedures-<cityId>.json} to the S3
 * procedures bucket.
 * The registry will initialise a helper for it on the first request that
 * carries that city in the JWT.
 */
@Singleton
public class ProcedureRagHelperRegistry {

    private static final Logger logger = Logger.getLogger(ProcedureRagHelperRegistry.class.getName());

    private final ConcurrentHashMap<String, ProcedureRagHelper> helpersByCity = new ConcurrentHashMap<>();

    @Inject
    public ProcedureRagHelperRegistry() {
    }

    /**
     * Returns a cached {@link ProcedureRagHelper} for the given city, building it
     * lazily on
     * first access. Subsequent calls for the same city return the same instance.
     *
     * @throws UncheckedIOException if the procedures file cannot be loaded for the
     *                              given city
     */
    public ProcedureRagHelper getForCity(String cityId) {
        return helpersByCity.computeIfAbsent(cityId, this::buildHelper);
    }

    private ProcedureRagHelper buildHelper(String cityId) {
        long startTime = System.currentTimeMillis();
        try {
            ProcedureRagHelper helper = new ProcedureRagHelper(cityId);
            long latency = System.currentTimeMillis() - startTime;
            int procedureCount = helper.getAllProcedures().size();
            logger.info(() -> "RAG INDEX BUILD — helper=ProcedureRagHelper cityId=" + cityId
                    + " latencyMs=" + latency + " procedures=" + procedureCount);
            return helper;
        } catch (IOException e) {
            long latency = System.currentTimeMillis() - startTime;
            logger.warning("RAG INDEX BUILD FAILED — helper=ProcedureRagHelper cityId=" + cityId
                    + " latencyMs=" + latency + " error=" + e.getMessage());
            throw new UncheckedIOException(
                    "Failed to initialise ProcedureRagHelper for city='" + cityId + "': " + e.getMessage(), e);
        }
    }
}
