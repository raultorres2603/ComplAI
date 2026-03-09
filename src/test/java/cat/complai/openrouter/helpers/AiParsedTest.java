package cat.complai.openrouter.helpers;

import cat.complai.openrouter.dto.OutputFormat;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiParsedTest {

    @Test
    void testParseSimpleHeader() {
        String input = "{\"format\":\"pdf\"}\n\nHere is the letter.";
        AiParsed parsed = AiParsed.parseAiFormatHeader(input);

        Assertions.assertEquals(OutputFormat.PDF, parsed.format());
        Assertions.assertEquals("Here is the letter.", parsed.message());
    }

    @Test
    void testParseJsonWithBody() {
        String input = "{\"format\":\"pdf\", \"message\": \"Content inside JSON\"}";
        AiParsed parsed = AiParsed.parseAiFormatHeader(input);

        Assertions.assertEquals(OutputFormat.PDF, parsed.format());
        Assertions.assertEquals("Content inside JSON", parsed.message());
    }

    @Test
    void testParseLazyGemini() {
        // Gemini sometimes outputs markdown code blocks or extra text
        String input = "```json\n{\"format\": \"pdf\"}\n```\nHere is the letter.";
        AiParsed parsed = AiParsed.parseAiFormatHeader(input);

        // Current logic expects strict start with {
        // This test documents current behavior: it should fail to parse header and return AUTO
        Assertions.assertEquals(OutputFormat.AUTO, parsed.format());
        Assertions.assertTrue(parsed.message().contains("{\"format\": \"pdf\"}"));
    }

    @Test
    void testParseHeaderWithWhitespace() {
        String input = "   {\"format\": \"pdf\"}   \n\nLetter content";
        AiParsed parsed = AiParsed.parseAiFormatHeader(input);

        // Current logic trims input before checking {
        Assertions.assertEquals(OutputFormat.PDF, parsed.format());
        Assertions.assertEquals("Letter content", parsed.message());
    }
}

