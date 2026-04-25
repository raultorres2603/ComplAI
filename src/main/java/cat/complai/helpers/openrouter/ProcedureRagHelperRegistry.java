package cat.complai.helpers.openrouter;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Thread-safe cache of per-city {@link RagHelper} instances for municipal procedures.
 *
 * <p>
 * Building the in-memory retrieval index is expensive (S3 I/O + index
 * construction). Each city's helper is initialised at most once per warm Lambda
 * instance and reused across all subsequent requests.
 *
 * <p>
 * To support a new city, upload {@code procedures-<cityId>.json} to the S3
 * procedures bucket. The registry will initialise a helper for it on the first
 * request that carries that city in the JWT.
 */
@Singleton
public class ProcedureRagHelperRegistry {

    private static final Logger logger = Logger.getLogger(ProcedureRagHelperRegistry.class.getName());

    private final ConcurrentHashMap<String, RagHelper<RagHelper.Procedure>> helpersByCity = new ConcurrentHashMap<>();

    /**
     * Constructs the registry.
     */
    @Inject
    public ProcedureRagHelperRegistry() {
    }

    /**
     * Returns a cached {@link RagHelper} for the given city's procedures, building it
     * lazily on first access. Subsequent calls for the same city return the same instance.
     *
     * @param cityId the city identifier
     * @return the procedure RAG helper
     */
    public RagHelper<RagHelper.Procedure> getForCity(String cityId) {
        return helpersByCity.computeIfAbsent(cityId, this::buildHelper);
    }

    private RagHelper<RagHelper.Procedure> buildHelper(String cityId) {
        long startTime = System.currentTimeMillis();
        RagHelper<RagHelper.Procedure> helper = RagHelper.forProcedures(cityId);
        long latency = System.currentTimeMillis() - startTime;
        int procedureCount = helper.getAll().size();
        logger.info(() -> "RAG INDEX BUILD — helper=ProcedureRagHelper cityId=" + cityId
                + " latencyMs=" + latency + " procedures=" + procedureCount);
        return helper;
    }
}
