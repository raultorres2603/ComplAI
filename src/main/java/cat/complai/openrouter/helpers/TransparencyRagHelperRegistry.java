package cat.complai.openrouter.helpers;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Singleton
public class TransparencyRagHelperRegistry {

    private static final Logger logger = Logger.getLogger(TransparencyRagHelperRegistry.class.getName());

    private final ConcurrentHashMap<String, TransparencyRagHelper> helpersByCity = new ConcurrentHashMap<>();

    @Inject
    public TransparencyRagHelperRegistry() {
    }

    public TransparencyRagHelper getForCity(String cityId) {
        return helpersByCity.computeIfAbsent(cityId, this::buildHelper);
    }

    private TransparencyRagHelper buildHelper(String cityId) {
        long startTime = System.currentTimeMillis();
        TransparencyRagHelper helper = new TransparencyRagHelper(cityId);
        long latency = System.currentTimeMillis() - startTime;
        int itemCount = helper.getAllTransparencyItems().size();
        logger.info(() -> "RAG INDEX BUILD — helper=TransparencyRagHelper cityId=" + cityId
                + " latencyMs=" + latency + " items=" + itemCount);
        return helper;
    }
}
