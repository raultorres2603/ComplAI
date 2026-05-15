package cat.complai.services.openrouter;

import cat.complai.dto.openrouter.Source;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result type for procedure context extraction: context block string and the
 * list of source URLs.
 */
public final class ProcedureContextResult {
    private final String contextBlock;
    private final List<Source> sources;

    public ProcedureContextResult(String contextBlock, List<Source> sources) {
        this.contextBlock = contextBlock;
        this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
    }

    public String getContextBlock() {
        return contextBlock;
    }

    public List<Source> getSources() {
        return sources;
    }
}
