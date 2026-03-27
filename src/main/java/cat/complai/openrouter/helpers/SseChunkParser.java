package cat.complai.openrouter.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses a single raw SSE line from an OpenRouter streaming response.
 * Returns the incremental text delta, or null if the line is a control
 * event (heartbeat, [DONE], empty line) or cannot be parsed.
 */
public final class SseChunkParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DONE_SENTINEL = "[DONE]";
    private static final String DATA_PREFIX = "data:";

    private SseChunkParser() {}

    /**
     * @param rawLine one line from the SSE stream (may include "data: " prefix)
     * @return the content delta text, empty string "" for keep-alive/empty-delta,
     *         or null for the terminal [DONE] event or unparseable input
     */
    public static String parseDelta(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) return "";
        String line = rawLine.stripLeading();
        if (!line.startsWith(DATA_PREFIX)) return "";
        String json = line.substring(DATA_PREFIX.length()).strip();
        if (json.equals(DONE_SENTINEL)) return null; // signals stream end
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode content = root.path("choices").path(0).path("delta").path("content");
            return content.isTextual() ? content.asText() : "";
        } catch (Exception e) {
            return ""; // malformed chunk: skip
        }
    }
}
