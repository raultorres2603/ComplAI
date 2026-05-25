package cat.complai.services.openrouter;

import cat.complai.dto.openrouter.Source;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result type for event context extraction: context block string and the
 * list of source URLs.
 */
public record EventContextResult(String contextBlock, List<Source> sources) {
    public EventContextResult(String contextBlock, List<Source> sources) {
        this.contextBlock = contextBlock;
        this.sources = sources == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sources));
    }
}
