package cat.complai.openrouter.helpers;

import cat.complai.openrouter.dto.OutputFormat;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiParsedTest {

    @Test
    void parseSimpleHeader_extractsFormatAndBody() {
        String input = "{\"format\":\"pdf\"}\n\nHere is the letter.";
        AiParsed parsed = AiParsed.parseAiFormatHeader(input);

        assertEquals(OutputFormat.PDF, parsed.format());
        assertEquals("Here is the letter.", parsed.message());
    }

    @Test
    void parseJsonWithInlineBodyKey_extractsBody() {
        String input = "{\"format\":\"pdf\", \"message\": \"Content inside JSON\"}";
        AiParsed parsed = AiParsed.parseAiFormatHeader(input);

        assertEquals(OutputFormat.PDF, parsed.format());
        assertEquals("Content inside JSON", parsed.message());
    }

    @Test
    void parseMarkdownFencedHeader_extractsFormatAndBody() {
        // Free models frequently wrap the JSON header in a markdown code fence even when
        // instructed not to. This used to return AUTO and silently discard the format,
        // causing PDF requests to fail or produce blank output.
        String input = "```json\n{\"format\": \"pdf\"}\n```\nHere is the letter.";
        AiParsed parsed = AiParsed.parseAiFormatHeader(input);

        assertEquals(OutputFormat.PDF, parsed.format());
        assertEquals("Here is the letter.", parsed.message());
    }

    @Test
    void parseMarkdownFencedHeaderWithBlankLineSeparator_extractsBody() {
        String input = "```json\n{\"format\": \"pdf\"}\n```\n\nDear Alcalde,\n\nAtentament.";
        AiParsed parsed = AiParsed.parseAiFormatHeader(input);

        assertEquals(OutputFormat.PDF, parsed.format());
        assertTrue(parsed.message().startsWith("Dear Alcalde,"));
    }

    @Test
    void parseHeaderWithLeadingWhitespace_extractsFormatAndBody() {
        String input = "   {\"format\": \"pdf\"}   \n\nLetter content";
        AiParsed parsed = AiParsed.parseAiFormatHeader(input);

        assertEquals(OutputFormat.PDF, parsed.format());
        assertEquals("Letter content", parsed.message());
    }

    @Test
    void parseHeaderWithNoBodyAfter_returnsBlankMessage() {
        // The model emitted only the header and forgot to write the letter.
        // AiParsed must still return the correct format so the caller can detect
        // the blank body and return a meaningful error instead of an empty PDF.
        String input = "{\"format\": \"pdf\"}";
        AiParsed parsed = AiParsed.parseAiFormatHeader(input);

        assertEquals(OutputFormat.PDF, parsed.format());
        assertTrue(parsed.message().isBlank(), "No body after header must yield a blank message");
    }

    @Test
    void parseNoHeaderAtAll_returnsAutoWithOriginalMessage() {
        String input = "Dear Alcalde,\n\nI am writing to complain.\n\nAtentament.";
        AiParsed parsed = AiParsed.parseAiFormatHeader(input);

        assertEquals(OutputFormat.AUTO, parsed.format());
        assertEquals(input, parsed.message());
    }

    @Test
    void parseNull_returnsAutoWithEmptyMessage() {
        AiParsed parsed = AiParsed.parseAiFormatHeader(null);

        assertEquals(OutputFormat.AUTO, parsed.format());
        assertEquals("", parsed.message());
    }
}
