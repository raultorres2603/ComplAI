package cat.complai.openrouter.helpers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Singleton
public class CityInfoRagHelperRegistry {

    private static final Logger logger = Logger.getLogger(CityInfoRagHelperRegistry.class.getName());

    private final ConcurrentHashMap<String, CityInfoRagHelper> helpersByCity = new ConcurrentHashMap<>();

    @Inject
    public CityInfoRagHelperRegistry() {
    }

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
