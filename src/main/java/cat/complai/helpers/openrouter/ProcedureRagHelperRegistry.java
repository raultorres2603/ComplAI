package cat.complai.helpers.openrouter;

import jakarta.inject.Singleton;

/**
 * Thread-safe cache of per-city {@link RagHelper} instances for municipal procedures.
 *
 * <p>
 * Building the in-memory retrieval index is expensive (S3 I/O + index
 * construction). Each city's helper is initialised at most once per warm Lambda
 * instance and reused across all subsequent requests.
 *
 * <p>
 * To support a new city, upload {@code procedures-<cityId>.json} to the S3
 * procedures bucket. The registry will initialise a helper for it on the first
 * request that carries that city in the JWT.
 */
@Singleton
public class ProcedureRagHelperRegistry extends RagHelperRegistry<RagHelper.Procedure> {

    /**
     * Constructs the registry for procedure RAG helpers.
     */
    public ProcedureRagHelperRegistry() {
        super(RagHelper::forProcedures, "ProcedureRagHelper", "procedures");
    }
}
