package cat.complai.helpers.openrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Parses a single raw SSE line from an OpenRouter streaming response.
 * Returns the incremental text delta, or null if the line is a control
 * event (heartbeat, [DONE], empty line) or cannot be parsed.
 *
 * <p>Receives the Micronaut-configured {@link ObjectMapper} via constructor
 * injection so that custom modules and configuration (e.g. date formats)
 * are applied consistently across the application.
 */
@Singleton
public final class SseChunkParser {

    public enum ParseState {
        IGNORE,
        DELTA,
        DONE,
        MALFORMED
    }

    public record ParseResult(ParseState state, String delta) {
        public static ParseResult ignore() {
            return new ParseResult(ParseState.IGNORE, "");
        }

        public static ParseResult delta(String delta) {
            return new ParseResult(ParseState.DELTA, delta);
        }

        public static ParseResult done() {
            return new ParseResult(ParseState.DONE, "");
        }

        public static ParseResult malformed() {
            return new ParseResult(ParseState.MALFORMED, "");
        }
    }

    private static final String DONE_SENTINEL = "[DONE]";
    private static final String DATA_PREFIX = "data:";

    private final ObjectMapper mapper;

    @Inject
    public SseChunkParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ParseResult parseLine(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) {
            return ParseResult.ignore();
        }

        String line = rawLine.stripLeading();
        if (line.startsWith(":")) {
            return ParseResult.ignore();
        }
        if (!line.startsWith(DATA_PREFIX)) {
            return ParseResult.ignore();
        }

        String json = line.substring(DATA_PREFIX.length()).strip();
        if (json.isEmpty()) {
            return ParseResult.ignore();
        }
        if (json.equals(DONE_SENTINEL)) {
            return ParseResult.done();
        }

        try {
            JsonNode root = mapper.readTree(json);
            JsonNode content = root.path("choices").path(0).path("delta").path("content");
            if (content.isTextual()) {
                return ParseResult.delta(content.asText());
            }
            // Valid JSON event without token delta (keep-alive/role/frame delimiter).
            if (root.has("choices") || root.has("id") || root.has("model")) {
                return ParseResult.ignore();
            }
            return ParseResult.malformed();
        } catch (Exception e) {
            return ParseResult.malformed();
        }
    }

    /**
     * @param rawLine one line from the SSE stream (may include "data: " prefix)
     * @return the content delta text, empty string "" for keep-alive/empty-delta,
     *         or null for the terminal [DONE] event or unparseable input
     */
    @Nullable
    public String parseDelta(String rawLine) {
        ParseResult parsed = parseLine(rawLine);
        if (parsed.state() == ParseState.DONE) {
            return null;
        }
        if (parsed.state() == ParseState.DELTA) {
            return parsed.delta();
        }
        return "";
    }
}
