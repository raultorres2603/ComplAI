package cat.complai.helpers.openrouter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SseChunkParserTest {

    private SseChunkParser parser;

    @BeforeEach
    void setUp() {
        parser = new SseChunkParser(new ObjectMapper());
    }

    @Test
    void parseDelta_validJsonChunk_returnsContentText() {
        String line = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}";
        assertEquals("Hello", parser.parseDelta(line));
    }

    @Test
    void parseDelta_doneSentinel_returnsNull() {
        assertNull(parser.parseDelta("data: [DONE]"));
    }

    @Test
    void parseDelta_emptyLine_returnsEmptyString() {
        assertEquals("", parser.parseDelta(""));
    }

    @Test
    void parseDelta_blankLine_returnsEmptyString() {
        assertEquals("", parser.parseDelta("   "));
    }

    @Test
    void parseDelta_nullLine_returnsEmptyString() {
        assertEquals("", parser.parseDelta(null));
    }

    @Test
    void parseDelta_malformedJson_returnsEmptyString() {
        String line = "data: {not valid json{{";
        assertEquals("", parser.parseDelta(line));
    }

    @Test
    void parseDelta_nonDataLine_returnsEmptyString() {
        assertEquals("", parser.parseDelta("event: ping"));
    }

    @Test
    void parseDelta_emptyDeltaContent_returnsEmptyString() {
        String line = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":null}]}";
        assertEquals("", parser.parseDelta(line));
    }

    @Test
    void parseDelta_deltaWithNullContent_returnsEmptyString() {
        String line = "data: {\"choices\":[{\"delta\":{\"content\":null},\"finish_reason\":\"stop\"}]}";
        assertEquals("", parser.parseDelta(line));
    }

    @Test
    void parseDelta_dataLineWithLeadingSpaces_parsesCorrectly() {
        String line = "  data: {\"choices\":[{\"delta\":{\"content\":\"World\"},\"finish_reason\":null}]}";
        assertEquals("World", parser.parseDelta(line));
    }

    @Test
    void parseLine_commentFrame_isIgnored() {
        SseChunkParser.ParseResult result = parser.parseLine(": OPENROUTER PROCESSING");
        assertEquals(SseChunkParser.ParseState.IGNORE, result.state());
    }

    @Test
    void parseLine_malformedData_isMarkedMalformed() {
        SseChunkParser.ParseResult result = parser.parseLine("data: {invalid-json");
        assertEquals(SseChunkParser.ParseState.MALFORMED, result.state());
    }
}
