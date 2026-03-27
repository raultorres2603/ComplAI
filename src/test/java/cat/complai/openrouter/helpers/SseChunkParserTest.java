package cat.complai.openrouter.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SseChunkParserTest {

    @Test
    void parseDelta_validJsonChunk_returnsContentText() {
        String line = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}";
        assertEquals("Hello", SseChunkParser.parseDelta(line));
    }

    @Test
    void parseDelta_doneSentinel_returnsNull() {
        assertEquals(null, SseChunkParser.parseDelta("data: [DONE]"));
    }

    @Test
    void parseDelta_emptyLine_returnsEmptyString() {
        assertEquals("", SseChunkParser.parseDelta(""));
    }

    @Test
    void parseDelta_blankLine_returnsEmptyString() {
        assertEquals("", SseChunkParser.parseDelta("   "));
    }

    @Test
    void parseDelta_nullLine_returnsEmptyString() {
        assertEquals("", SseChunkParser.parseDelta(null));
    }

    @Test
    void parseDelta_malformedJson_returnsEmptyString() {
        String line = "data: {not valid json{{";
        assertEquals("", SseChunkParser.parseDelta(line));
    }

    @Test
    void parseDelta_nonDataLine_returnsEmptyString() {
        assertEquals("", SseChunkParser.parseDelta("event: ping"));
    }

    @Test
    void parseDelta_emptyDeltaContent_returnsEmptyString() {
        String line = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":null}]}";
        assertEquals("", SseChunkParser.parseDelta(line));
    }

    @Test
    void parseDelta_deltaWithNullContent_returnsEmptyString() {
        String line = "data: {\"choices\":[{\"delta\":{\"content\":null},\"finish_reason\":\"stop\"}]}";
        assertEquals("", SseChunkParser.parseDelta(line));
    }

    @Test
    void parseDelta_dataLineWithLeadingSpaces_parsesCorrectly() {
        String line = "  data: {\"choices\":[{\"delta\":{\"content\":\"World\"},\"finish_reason\":null}]}";
        assertEquals("World", SseChunkParser.parseDelta(line));
    }
}
