package cat.complai.openrouter.helpers;

import cat.complai.openrouter.dto.OutputFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Small holder for parsed AI reply header and message body.
 * Used by OpenRouterServices to parse and pass AI response metadata.
 */
public record AiParsed(OutputFormat format, String message) {

    /**
     * Parse a model reply for an optional metadata header. Supports:
     * - First-line JSON like: {"format":"pdf"}\n\n<message>
     * - First-line simple header like: FORMAT: pdf\n\n<message>
     * If no header found, returns format AUTO and the original message.
     */
    public static AiParsed parseAiFormatHeader(String aiMessage) {
        // Strict: only accept a JSON header on the first line. If not present, treat as missing header.
        if (aiMessage == null) return new AiParsed(OutputFormat.AUTO, "");
        String trimmed = aiMessage.trim();

        if (!trimmed.startsWith("{")) {
            return new AiParsed(OutputFormat.AUTO, aiMessage);
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            // Read first JSON object at the start: find the closing brace matching the first open
            int depth = 0;
            int idx = -1;
            for (int i = 0; i < aiMessage.length(); i++) {
                char c = aiMessage.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        idx = i;
                        break;
                    }
                }
            }
            if (idx < 0) return new AiParsed(OutputFormat.AUTO, aiMessage);

            String jsonHeader = aiMessage.substring(0, idx + 1).trim();
            String rest = aiMessage.substring(idx + 1).trim();
            Map<String, Object> map = mapper.readValue(jsonHeader, new TypeReference<>() {
            });
            Object fmt = map.get("format");
            Object body = map.get("body") != null ? map.get("body") : map.get("message");
            OutputFormat f = fmt == null ? OutputFormat.AUTO : OutputFormat.fromString(fmt.toString());
            String m = (body != null) ? body.toString() : rest;
            // If body missing in JSON, use the remaining text after the JSON header (if non-blank)
            if ((m == null || m.isBlank()) && !rest.isBlank()) m = rest;
            if (m == null) m = "";
            return new AiParsed(f, m.trim());
        } catch (Exception e) {
            // parsing failed: treat as missing strict header
            return new AiParsed(OutputFormat.AUTO, aiMessage);
        }
    }
}
