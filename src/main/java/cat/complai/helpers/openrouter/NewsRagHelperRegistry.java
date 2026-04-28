package cat.complai.helpers.openrouter;

import jakarta.inject.Singleton;

/**
 * Thread-safe cache of per-city {@link RagHelper} instances for city news.
 *
 * <p>
 * Building the in-memory retrieval index is expensive (S3 I/O + index
 * construction). Each city's helper is initialised at most once per warm Lambda
 * instance and reused across all subsequent requests.
 *
 * <p>
 * To support a new city, upload {@code news-<cityId>.json} to the S3 news
 * bucket.
 */
@Singleton
public class NewsRagHelperRegistry extends RagHelperRegistry<RagHelper.News> {

    /**
     * Constructs the registry for news RAG helpers.
     */
    public NewsRagHelperRegistry() {
        super(RagHelper::forNews, "NewsRagHelper", "news");
    }
}
