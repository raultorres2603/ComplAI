package cat.complai.services.openrouter;

/**
 * Captures which RAG context domains are required for a given query.
 */
public final class ContextRequirements {

    private static final ContextRequirements NONE = new ContextRequirements(false, false, false, false);

    private final boolean needsProcedureContext;
    private final boolean needsEventContext;
    private final boolean needsNewsContext;
    private final boolean needsCityInfoContext;

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
    public ContextRequirements(boolean needsProcedureContext, boolean needsEventContext, boolean needsNewsContext,
            boolean needsCityInfoContext) {
        this.needsProcedureContext = needsProcedureContext;
        this.needsEventContext = needsEventContext;
        this.needsNewsContext = needsNewsContext;
        this.needsCityInfoContext = needsCityInfoContext;
    }

    /**
     * Returns an instance that requires no context at all.
     *
     * @return shared NONE instance
     */
    public static ContextRequirements none() {
        return NONE;
    }

    /**
     * Returns {@code true} if procedure context should be fetched.
     *
     * @return needs-procedure flag
     */
    public boolean needsProcedureContext() {
        return needsProcedureContext;
    }

    /**
     * Returns {@code true} if event context should be fetched.
     *
     * @return needs-event flag
     */
    public boolean needsEventContext() {
        return needsEventContext;
    }

    /**
     * Returns {@code true} if news context should be fetched.
     *
     * @return needs-news flag
     */
    public boolean needsNewsContext() {
        return needsNewsContext;
    }

    /**
     * Returns {@code true} if city-info context should be fetched.
     *
     * @return needs-city-info flag
     */
    public boolean needsCityInfoContext() {
        return needsCityInfoContext;
    }
}
