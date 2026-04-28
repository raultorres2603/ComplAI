package cat.complai;

import io.micronaut.runtime.Micronaut;

/**
 * ComplAI Lambda Application Entry Point.
 *
 * <p>
 * Micronaut startup initializes all @Singleton beans but does NOT eagerly load
 * RAG data.
 * AWS SDK clients (S3, SQS) are initialized on startup (low overhead,
 * necessary).
 * RAG helper indexes ({@link cat.complai.helpers.openrouter.RagHelperRegistry}) are
 * built lazily
 * on first request for each city using ConcurrentHashMap.computeIfAbsent().
 * 
 * This design minimizes Lambda cold-start latency while preserving
 * functionality.
 * 
 * <p>
 * To monitor initialization: Check logs for "RAG INDEX BUILD" messages when
 * cities are first accessed.
 * Use GET /health/startup to verify Lambda startup without triggering RAG
 * initialization.
 */
public class App {
    public static void main(String[] args) {
        Micronaut.run(App.class, args);
    }
}
