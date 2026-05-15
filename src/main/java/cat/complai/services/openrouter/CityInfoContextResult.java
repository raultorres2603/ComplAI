package cat.complai.services.openrouter;

import cat.complai.dto.openrouter.Source;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result type for city-information context extraction.
 */
public final class CityInfoContextResult {
    private final String contextBlock;
    private final List<Source> sources;

    /**
     * Constructs a city-info context result.
     *
     * @param contextBlock the RAG context text to inject into the AI prompt
     * @param sources      the source documents used to build the context
     */
    public CityInfoContextResult(String contextBlock, List<Source> sources) {
        this.contextBlock = contextBlock;
        this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
    }

    /**
     * Returns the RAG context text snippet for the AI prompt.
     *
     * @return context block text
     */
    public String getContextBlock() {
        return contextBlock;
    }

    /**
     * Returns the unmodifiable list of source documents.
     *
     * @return list of sources
     */
    public List<Source> getSources() {
        return sources;
    }
}
