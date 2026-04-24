package cat.complai.helpers.openrouter;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Thread-safe cache of per-city {@link CityInfoRagHelper} instances.
 *
 * <p>
 * Building the in-memory retrieval index is expensive (S3 I/O + index
 * construction). Each
 * city's helper is initialised at most once per warm Lambda instance and reused
 * across all
 * subsequent requests.
 *
 * <p>
 * To support a new city, upload {@code cityinfo-<cityId>.json} to the S3
 * city-info bucket.
 */
@Singleton
public class CityInfoRagHelperRegistry {

    private static final Logger logger = Logger.getLogger(CityInfoRagHelperRegistry.class.getName());

    private final ConcurrentHashMap<String, CityInfoRagHelper> helpersByCity = new ConcurrentHashMap<>();

    /**
     * Constructs the registry.
     */
    @Inject
    public CityInfoRagHelperRegistry() {
    }

    /**
     * Returns a cached {@link CityInfoRagHelper} for the given city, building it
     * lazily on
     * first access.
     *
     * @param cityId the city identifier
     * @return the city-info RAG helper
     */
    public CityInfoRagHelper getForCity(String cityId) {
        return helpersByCity.computeIfAbsent(cityId, this::buildHelper);
    }

    private CityInfoRagHelper buildHelper(String cityId) {
        long startTime = System.currentTimeMillis();
        CityInfoRagHelper helper = new CityInfoRagHelper(cityId);
        long latency = System.currentTimeMillis() - startTime;
        int cityInfoCount = helper.getAllCityInfo().size();
        logger.info(() -> "RAG INDEX BUILD - helper=CityInfoRagHelper cityId=" + cityId
                + " latencyMs=" + latency + " cityInfo=" + cityInfoCount);
        return helper;
    }
}
