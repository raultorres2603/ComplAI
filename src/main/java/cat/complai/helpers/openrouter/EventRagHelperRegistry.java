package cat.complai.helpers.openrouter;

import jakarta.inject.Singleton;

/**
 * Thread-safe cache of per-city {@link RagHelper} instances for city events.
 *
 * <p>
 * Building the in-memory retrieval index is expensive (S3 I/O + index
 * construction). Each city's helper is initialised at most once per warm Lambda
 * instance and reused across all subsequent requests.
 *
 * <p>
 * To support a new city, upload {@code events-<cityId>.json} to the S3 events
 * bucket. The registry will initialise a helper for it on the first request
 * that carries that city in the JWT.
 */
@Singleton
public class EventRagHelperRegistry extends RagHelperRegistry<RagHelper.Event> {

    /**
     * Constructs the registry for event RAG helpers.
     */
    public EventRagHelperRegistry() {
        super(RagHelper::forEvents, "EventRagHelper", "events");
    }
}
