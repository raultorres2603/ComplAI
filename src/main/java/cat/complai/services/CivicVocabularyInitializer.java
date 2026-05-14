package cat.complai.services;

import cat.complai.config.CivicVocabularyConfig;
import cat.complai.helpers.openrouter.CivicVocabularyService;
import cat.complai.helpers.openrouter.RagHelper;
import jakarta.inject.Singleton;
import jakarta.annotation.PostConstruct;
import java.util.logging.Logger;

/**
 * Initializes civic vocabulary services at application startup.
 *
 * <p>
 * This service injects the CivicVocabularyService and CivicVocabularyConfig
 * at startup and configures them in RagHelper so that all RAG searches
 * automatically use civic vocabulary expansion.
 */
@Singleton
public class CivicVocabularyInitializer {

    private static final Logger logger = Logger.getLogger(CivicVocabularyInitializer.class.getName());

    private final CivicVocabularyService civicVocabularyService;
    private final CivicVocabularyConfig civicVocabularyConfig;

    public CivicVocabularyInitializer(
            CivicVocabularyService civicVocabularyService,
            CivicVocabularyConfig civicVocabularyConfig) {
        this.civicVocabularyService = civicVocabularyService;
        this.civicVocabularyConfig = civicVocabularyConfig;
    }

    @PostConstruct
    public void initialize() {
        logger.info(() -> "Initializing civic vocabulary services...");
        RagHelper.setCivicVocabularyServices(civicVocabularyService, civicVocabularyConfig);
        logger.info(() -> "Civic vocabulary services initialized (enabled=" +
                civicVocabularyConfig.isEnabled() + ")");
    }
}