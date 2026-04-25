package cat.complai.helpers.openrouter;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Thread-safe cache of per-city {@link RagHelper} instances for city-information pages.
 *
 * <p>
 * Building the in-memory retrieval index is expensive (S3 I/O + index
 * construction). Each city's helper is initialised at most once per warm Lambda
 * instance and reused across all subsequent requests.
 *
 * <p>
 * To support a new city, upload {@code cityinfo-<cityId>.json} to the S3
 * city-info bucket.
 */
@Singleton
public class CityInfoRagHelperRegistry {

    private static final Logger logger = Logger.getLogger(CityInfoRagHelperRegistry.class.getName());

    private final ConcurrentHashMap<String, RagHelper<RagHelper.CityInfo>> helpersByCity = new ConcurrentHashMap<>();

    /**
     * Constructs the registry.
     */
    @Inject
    public CityInfoRagHelperRegistry() {
    }

    /**
     * Returns a cached {@link RagHelper} for the given city's city-info pages, building it
     * lazily on first access.
     *
     * @param cityId the city identifier
     * @return the city-info RAG helper
     */
    public RagHelper<RagHelper.CityInfo> getForCity(String cityId) {
        return helpersByCity.computeIfAbsent(cityId, this::buildHelper);
    }

    private RagHelper<RagHelper.CityInfo> buildHelper(String cityId) {
        long startTime = System.currentTimeMillis();
        RagHelper<RagHelper.CityInfo> helper = RagHelper.forCityInfo(cityId);
        long latency = System.currentTimeMillis() - startTime;
        int cityInfoCount = helper.getAll().size();
        logger.info(() -> "RAG INDEX BUILD - helper=CityInfoRagHelper cityId=" + cityId
                + " latencyMs=" + latency + " cityInfo=" + cityInfoCount);
        return helper;
    }
}
