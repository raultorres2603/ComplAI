package cat.complai.openrouter.helpers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Thread-safe cache of per-city {@link NewsRagHelper} instances.
 *
 * <p>
 * Building the in-memory retrieval index is expensive (S3 I/O + index
 * construction). Each
 * city's helper is initialised at most once per warm Lambda instance and reused
 * across all
 * subsequent requests.
 *
 * <p>
 * To support a new city, upload {@code news-<cityId>.json} to the S3 news
 * bucket.
 */
@Singleton
public class NewsRagHelperRegistry {

    private static final Logger logger = Logger.getLogger(NewsRagHelperRegistry.class.getName());

    private final ConcurrentHashMap<String, NewsRagHelper> helpersByCity = new ConcurrentHashMap<>();

    /**
     * Constructs the registry.
     */
    @Inject
    public NewsRagHelperRegistry() {
    }

    /**
     * Returns a cached {@link NewsRagHelper} for the given city, building it lazily
     * on
     * first access.
     *
     * @param cityId the city identifier
     * @return the news RAG helper
     */
    public NewsRagHelper getForCity(String cityId) {
        return helpersByCity.computeIfAbsent(cityId, this::buildHelper);
    }

    private NewsRagHelper buildHelper(String cityId) {
        long startTime = System.currentTimeMillis();
        NewsRagHelper helper = new NewsRagHelper(cityId);
        long latency = System.currentTimeMillis() - startTime;
        int newsCount = helper.getAllNews().size();
        logger.info(() -> "RAG INDEX BUILD - helper=NewsRagHelper cityId=" + cityId
                + " latencyMs=" + latency + " news=" + newsCount);
        return helper;
    }
}