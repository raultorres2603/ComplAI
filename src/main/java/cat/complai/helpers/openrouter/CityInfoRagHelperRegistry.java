package cat.complai.helpers.openrouter;

import jakarta.inject.Singleton;

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
public class CityInfoRagHelperRegistry extends RagHelperRegistry<RagHelper.CityInfo> {

    /**
     * Constructs the registry for city-info RAG helpers.
     */
    public CityInfoRagHelperRegistry() {
        super(RagHelper::forCityInfo, "CityInfoRagHelper", "cityInfo");
    }
}
