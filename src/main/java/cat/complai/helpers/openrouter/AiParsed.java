package cat.complai.helpers.openrouter;

import cat.complai.dto.openrouter.OutputFormat;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small holder for parsed AI reply header and message body.
 * Used by OpenRouterServices to parse and pass AI response metadata.
 */
public record AiParsed(OutputFormat format, String message) {

    // Matches an optional markdown code fence wrapping a JSON object.
    // Free models frequently wrap their header in ```json ... ``` even when told not to.
    private static final Pattern MARKDOWN_FENCE_PATTERN =
            Pattern.compile("^```(?:json)?\\s*\\n?(\\{[^`]+})[^`]*```\\s*\\n?(.*)", Pattern.DOTALL);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parses a model reply for the mandatory JSON metadata header.
     *
     * Supported response shapes (in priority order):
     *   1. Clean first-line JSON:       {"format":"pdf"}\n\n<letter body>
     *   2. Markdown-fenced JSON:        ```json\n{"format":"pdf"}\n```\n\n<letter body>
     *   3. JSON with body key inline:   {"format":"pdf","body":"<letter body>"}
     *
     * In shapes 1 and 2, the letter body is the text that follows the closing brace / fence.
     * If the letter body turns out to be empty after parsing (the model forgot to write it),
     * the returned message will be blank — callers must handle this case and return an error
     * rather than generating an empty PDF.
     *
     * If no recognisable header is found, returns format AUTO with the original message so
     * the caller can apply its graceful fallback.
     */
    public static AiParsed parseAiFormatHeader(String aiMessage) {
        if (aiMessage == null) return new AiParsed(OutputFormat.AUTO, "");

        String trimmed = aiMessage.trim();

        // Shape 2: markdown-fenced JSON header (e.g. ```json\n{"format":"pdf"}\n```)
        Matcher fenceMatcher = MARKDOWN_FENCE_PATTERN.matcher(trimmed);
        if (fenceMatcher.matches()) {
            String jsonPart = fenceMatcher.group(1).trim();
            String bodyPart = fenceMatcher.group(2).trim();
            return parseJsonHeaderAndBody(jsonPart, bodyPart, aiMessage);
        }

        // Shape 1 & 3: response starts directly with a JSON object
        if (!trimmed.startsWith("{")) {
            return new AiParsed(OutputFormat.AUTO, aiMessage);
        }

        int closingBraceIdx = findMatchingClosingBrace(aiMessage);
        if (closingBraceIdx < 0) {
            return new AiParsed(OutputFormat.AUTO, aiMessage);
        }

        String jsonHeader = aiMessage.substring(0, closingBraceIdx + 1).trim();
        String rest = aiMessage.substring(closingBraceIdx + 1).trim();
        return parseJsonHeaderAndBody(jsonHeader, rest, aiMessage);
    }

    /**
     * Parses a JSON header string and combines it with the letter body that follows it.
     * Falls back to AUTO + original message if the JSON is invalid.
     */
    private static AiParsed parseJsonHeaderAndBody(String jsonHeader, String bodyAfterHeader, String originalMessage) {
        try {
            Map<String, Object> map = MAPPER.readValue(jsonHeader, new TypeReference<>() {});

            Object fmt = map.get("format");
            // Check common body key names the model might inline into the JSON object itself
            Object inlineBody = map.get("body");
            if (inlineBody == null) inlineBody = map.get("message");
            if (inlineBody == null) inlineBody = map.get("content");
            if (inlineBody == null) inlineBody = map.get("text");
            if (inlineBody == null) inlineBody = map.get("response");

            OutputFormat resolvedFormat = fmt == null ? OutputFormat.AUTO : OutputFormat.fromString(fmt.toString());

            // Prefer inline body key; fall back to text after the closing brace
            String resolvedBody = (inlineBody != null) ? inlineBody.toString().trim() : bodyAfterHeader;
            if (resolvedBody == null) resolvedBody = "";

            return new AiParsed(resolvedFormat, resolvedBody);
        } catch (Exception e) {
            // JSON was malformed — treat as missing header
            return new AiParsed(OutputFormat.AUTO, originalMessage);
        }
    }

    /**
     * Finds the index of the closing brace that matches the first opening brace in the string.
     * Returns -1 if the string has no balanced braces.
     */
    private static int findMatchingClosingBrace(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
