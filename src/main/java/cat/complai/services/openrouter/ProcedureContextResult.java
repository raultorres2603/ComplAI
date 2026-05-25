package cat.complai.services.openrouter;

import cat.complai.dto.openrouter.Source;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result type for procedure context extraction: context block string and the
 * list of source URLs.
 */
public record ProcedureContextResult(String contextBlock, List<Source> sources) {
    public ProcedureContextResult(String contextBlock, List<Source> sources) {
        this.contextBlock = contextBlock;
        this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
    }
}
