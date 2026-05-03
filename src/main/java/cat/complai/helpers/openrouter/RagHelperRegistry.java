package cat.complai.helpers.openrouter;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Thread-safe cache of per-city {@link RagHelper} instances for a specific domain.
 *
 * <p>
 * Building the in-memory retrieval index is expensive (S3 I/O + index
 * construction). Each city's helper is initialised at most once per warm Lambda
 * instance and reused across all subsequent requests.
 *
 * @param <T> the domain type stored in the index (e.g., {@link RagHelper.News},
 *            {@link RagHelper.Event}, {@link RagHelper.CityInfo},
 *            {@link RagHelper.Procedure})
 */
@Singleton
public abstract class RagHelperRegistry<T> {

    private static final Logger logger = Logger.getLogger(RagHelperRegistry.class.getName());

    private final ConcurrentHashMap<String, RagHelper<T>> helpersByCity = new ConcurrentHashMap<>();
    private final Function<String, RagHelper<T>> factory;
    private final String helperName;
    private final String itemLabel;

    /**
     * Constructs a registry with the specified factory and logging labels.
     *
     * @param factory    function to create a new RagHelper for a given cityId
     * @param helperName name used in log messages (e.g., "NewsRagHelper")
     * @param itemLabel  label for items in log messages (e.g., "news")
     */
    @Inject
    public RagHelperRegistry(Function<String, RagHelper<T>> factory, String helperName, String itemLabel) {
        this.factory = factory;
        this.helperName = helperName;
        this.itemLabel = itemLabel;
    }

    /**
     * Returns a cached {@link RagHelper} for the given city, building it
     * lazily on first access. Subsequent calls for the same city return the same instance.
     *
     * @param cityId the city identifier
     * @return the RAG helper for this registry's domain
     */
    public RagHelper<T> getForCity(String cityId) {
        return helpersByCity.computeIfAbsent(cityId, this::buildHelper);
    }

    private RagHelper<T> buildHelper(String cityId) {
        long startTime = System.currentTimeMillis();
        RagHelper<T> helper = factory.apply(cityId);
        long latency = System.currentTimeMillis() - startTime;
        int itemCount = helper.getAll().size();
        String separator = " - ".equals(helperName) ? " - " : " — ";
        logger.info(() -> "RAG INDEX BUILD" + separator + "helper=" + helperName + " cityId=" + cityId
                + " latencyMs=" + latency + " " + itemLabel + "=" + itemCount);
        return helper;
    }
}
