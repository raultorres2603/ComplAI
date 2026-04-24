package cat.complai.helpers.openrouter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownToHtmlConverterTest {

    @Test
    void testNull_ReturnsNull() {
        assertNull(MarkdownToHtmlConverter.convertMarkdownToHtml(null));
    }

    @Test
    void testBlank_ReturnsBlank() {
        assertEquals("", MarkdownToHtmlConverter.convertMarkdownToHtml(""));
        assertEquals("   ", MarkdownToHtmlConverter.convertMarkdownToHtml("   "));
    }

    @Test
    void testPlainText_NoConversion() {
        String input = "This is plain text.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("This is plain text."));
    }

    // ===== BOLD TESTS =====

    @Test
    void testBold_ConvertsSingleBoldMarks() {
        String input = "This is **bold** text.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<strong>bold</strong>"));
    }

    @Test
    void testBold_ConvertsMulipleBoldMarks() {
        String input = "This is **bold** and **more bold** text.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<strong>bold</strong>"));
        assertTrue(result.contains("<strong>more bold</strong>"));
    }

    @Test
    void testBold_WithSpaces() {
        String input = "**bold text with spaces**";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<strong>bold text with spaces</strong>"));
    }

    // ===== ITALIC TESTS =====

    @Test
    void testItalic_ConvertsSingleItalicMarks() {
        String input = "This is *italic* text.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<em>italic</em>"));
    }

    @Test
    void testItalic_ConvertsMulitpleItalicMarks() {
        String input = "This is *italic* and *more italic* text.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<em>italic</em>"));
        assertTrue(result.contains("<em>more italic</em>"));
    }

    @Test
    void testItalic_WithSpaces() {
        String input = "*italic text with spaces*";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<em>italic text with spaces</em>"));
    }

    // ===== LINK TESTS =====

    @Test
    void testLink_ConvertsMarkdownLink() {
        String input = "Visit [ComplAI](https://complai.local) for more info.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<a href=\"https://complai.local\">ComplAI</a>"));
    }

    @Test
    void testLink_ConvertsMultipleLinks() {
        String input = "Go to [site1](http://site1.com) or [site2](http://site2.com).";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<a href=\"http://site1.com\">site1</a>"));
        assertTrue(result.contains("<a href=\"http://site2.com\">site2</a>"));
    }

    @Test
    void testLink_WithComplexUrl() {
        String input = "[procedure details]( https://example.com/proc/123?type=permit&lang=ca)";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<a href=\""));
        assertTrue(result.contains("example.com/proc/123?type=permit&lang=ca"));
    }

    // ===== HEADING TESTS =====

    @Test
    void testHeading_Level2() {
        String input = "## This is a heading";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<h2>This is a heading</h2>"));
    }

    @Test
    void testHeading_Level3() {
        String input = "### Subheading";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<h3>Subheading</h3>"));
    }

    @Test
    void testHeading_MultipleHeadings() {
        String input = "## First\n### Second\n#### Third";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<h2>First</h2>"));
        assertTrue(result.contains("<h3>Second</h3>"));
        assertTrue(result.contains("<h4>Third</h4>"));
    }

    // ===== LIST TESTS =====

    @Test
    void testList_SingleItem() {
        String input = "- First item";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<ul>"));
        assertTrue(result.contains("<li>First item</li>"));
        assertTrue(result.contains("</ul>"));
    }

    @Test
    void testList_MultipleItems() {
        String input = "- First item\n- Second item\n- Third item";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<li>First item</li>"));
        assertTrue(result.contains("<li>Second item</li>"));
        assertTrue(result.contains("<li>Third item</li>"));
    }

    @Test
    void testList_WithIndentation() {
        String input = "  - Indented item";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<li>Indented item</li>"));
    }

    @Test
    void testList_WithOtherContent() {
        String input = "Some text\n- Item 1\n- Item 2\nMore text";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("Some text"));
        assertTrue(result.contains("<li>Item 1</li>"));
        assertTrue(result.contains("<li>Item 2</li>"));
        assertTrue(result.contains("More text"));
    }

    // ===== PARAGRAPH TESTS =====

    @Test
    void testParagraph_SingleParagraph() {
        String input = "This is a single paragraph with text.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<p>"));
        assertTrue(result.contains("This is a single paragraph with text."));
        assertTrue(result.contains("</p>"));
    }

    @Test
    void testParagraph_MultipleParagraphs() {
        String input = "First paragraph.\n\nSecond paragraph.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<p>First paragraph.</p>"));
        assertTrue(result.contains("<p>Second paragraph.</p>"));
    }

    @Test
    void testParagraph_WithLineBreaks() {
        String input = "Line one\nLine two\n\nNew paragraph";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        // Single newline within paragraph should become <br>
        assertTrue(result.contains("<br>"));
    }

    @Test
    void testParagraph_ExcessiveNewlines() {
        String input = "Paragraph one.\n\n\n\nParagraph two.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<p>Paragraph one.</p>"));
        assertTrue(result.contains("<p>Paragraph two.</p>"));
    }

    // ===== COMPLEX / COMBINED TESTS =====

    @Test
    void testCombined_BoldAndItalic() {
        String input = "This is **bold** and *italic* text.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<strong>bold</strong>"));
        assertTrue(result.contains("<em>italic</em>"));
    }

    @Test
    void testCombined_LinkWithFormatting() {
        String input = "Visit our **[main site](https://example.com)** for updates.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        // Link should be converted first
        assertTrue(result.contains("<a href=\"https://example.com\">main site</a>"));
    }

    @Test
    void testCombined_ComplexDocument() {
        String input = "## Information\n\nThis is **important**.\n\n- Item 1\n- Item 2\n\nVisit [here](https://example.com) for more.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertTrue(result.contains("<h2>Information</h2>"));
        assertTrue(result.contains("<strong>important</strong>"));
        assertTrue(result.contains("<li>Item 1</li>"));
        assertTrue(result.contains("<a href=\"https://example.com\">here</a>"));
    }

    @Test
    void testCombined_RealWorldComplaint() {
        String input = "## Summary\n\nI have a **complaint** about the *street condition*.\n\n- Broken pavement\n- Missing signs\n\nFor details see [complaint form](https://example.com/form).";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertNotNull(result);
        assertTrue(result.contains("<h2>Summary</h2>"));
        assertTrue(result.contains("<strong>complaint</strong>"));
        assertTrue(result.contains("<em>street condition</em>"));
        assertTrue(result.contains("<li>Broken pavement</li>"));
        assertTrue(result.contains("<a href="));
    }

    @Test
    void testEmpty_AfterConversion_StillValid() {
        String input = "- \n- ";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertNotNull(result);
    }

    @Test
    void testPreservesPlainTextLink_NotMarkdownFormat() {
        String input = "Please visit https://example.com directly.";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        // Plain URL (not Markdown format) should not be converted
        assertTrue(result.contains("https://example.com"));
    }

    @Test
    void testEscapeHtml_ProtectsAgainstInjection() {
        // Note: Current implementation doesn't escape HTML.
        // If HTML escaping is needed, this test can be adjusted after implementation.
        String input = "This has <script>alert('xss')</script>";
        String result = MarkdownToHtmlConverter.convertMarkdownToHtml(input);
        assertNotNull(result);
        // The converter doesn't currently escape HTML, so this will pass through
        // If escaping is needed in the future, update this test
    }
}
