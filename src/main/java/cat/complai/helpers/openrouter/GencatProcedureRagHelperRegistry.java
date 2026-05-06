package cat.complai.helpers.openrouter;

import jakarta.inject.Singleton;

/**
 * Thread-safe cache of {@link RagHelper} instance for Generalitat de Catalunya procedures.
 *
 * <p>
 * This registry is a singleton that provides a RAG helper for the gencat procedures.
 * It's used as a fallback when:
 * <ul>
 *   <li>User explicitly asks for "Generalitat" or "Generalitat de Catalunya" procedures</li>
 *   <li>City-specific procedures don't return any matching results</li>
 * </ul>
 *
 * <p>
 * The procedures are loaded from S3 at {@code procedures-gencat.json}.
 */
@Singleton
public class GencatProcedureRagHelperRegistry extends RagHelperRegistry<RagHelper.Procedure> {

    /**
     * Constructs the registry for Generalitat procedure RAG helpers.
     */
    public GencatProcedureRagHelperRegistry() {
        // Use fixed "gencat" city ID to load procedures-gencat.json
        super(cityId -> RagHelper.forProcedures("gencat"), "GencatProcedureRagHelper", "procedures");
    }
}