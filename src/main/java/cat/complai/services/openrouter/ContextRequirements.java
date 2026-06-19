package cat.complai.services.openrouter;

/**
 * Captures which RAG context domains are required for a given query.
 */
public record ContextRequirements(boolean needsProcedureContext, boolean needsEventContext, boolean needsNewsContext,
                                  boolean needsCityInfoContext) {

    private static final ContextRequirements NONE = new ContextRequirements(false, false, false, false);

    /**
     * Constructs a requirements descriptor.
     *
     * @param needsProcedureContext {@code true} if procedure context should be
     *                              fetched
     * @param needsEventContext     {@code true} if event context should be fetched
     * @param needsNewsContext      {@code true} if news context should be fetched
     * @param needsCityInfoContext  {@code true} if city-info context should be
     *                              fetched
     */
    public ContextRequirements {
    }

    /**
     * Returns an instance that requires no context at all.
     *
     * @return shared NONE instance
     */
    public static ContextRequirements none() {
        return NONE;
    }
}
