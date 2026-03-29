package cat.complai.openrouter.helpers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Singleton
public class NewsRagHelperRegistry {

    private static final Logger logger = Logger.getLogger(NewsRagHelperRegistry.class.getName());

    private final ConcurrentHashMap<String, NewsRagHelper> helpersByCity = new ConcurrentHashMap<>();

    @Inject
    public NewsRagHelperRegistry() {
    }

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