package cat.complai.helpers.openrouter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HtmlFormatter.
 * Tests cover removal of excessive newlines around HTML tags while preserving
 * meaningful content breaks.
 */
@DisplayName("HtmlFormatter Tests")
class HtmlFormatterTest {

    @Test
    @DisplayName("Should remove newlines around block-level tags")
    void testRemoveNewlinesAroundBlockLevelTags() {
        String input = "\n<ul>\n<li>Item 1</li>\n<li>Item 2</li>\n</ul>\n";
        String expected = "<ul><li>Item 1</li><li>Item 2</li></ul>";
        String result = HtmlFormatter.cleanHtml(input);
        assertEquals(expected, result);
    }

    @Test
    @DisplayName("Should preserve meaningful newlines in content")
    void testPreserveMeaningfulNewlinesInContent() {
        String input = "Paragraph 1\n\nParagraph 2\n<p>Text</p>";
        String result = HtmlFormatter.cleanHtml(input);
        // Should preserve double newlines between paragraphs
        assertTrue(result.contains("Paragraph 1\n\nParagraph 2"));
        // But remove newline before tag
        assertFalse(result.contains("Paragraph 2\n<p>"));
    }

    @Test
    @DisplayName("Should handle mixed HTML with text content")
    void testHandleMixedHtmlWithText() {
        String input = "Some text here\n<ul>\n<li>Item 1</li>\n</ul>\nMore text";
        String result = HtmlFormatter.cleanHtml(input);
        // Should remove newlines immediately before tags
        assertFalse(result.contains("here\n<ul>"));
        assertFalse(result.contains("here\n<"));
        // But should preserve meaningful newlines around text content
        assertTrue(result.contains("</ul>\nMore"));
    }

    @Test
    @DisplayName("Should handle null input")
    void testHandleNullInput() {
        String result = HtmlFormatter.cleanHtml(null);
        assertNull(result);
    }

    @Test
    @DisplayName("Should handle empty string input")
    void testHandleEmptyStringInput() {
        String result = HtmlFormatter.cleanHtml("");
        assertEquals("", result);
    }

    @Test
    @DisplayName("Should handle blank string input")
    void testHandleBlankStringInput() {
        String result = HtmlFormatter.cleanHtml("   ");
        assertTrue(result.isBlank());
    }

    @Test
    @DisplayName("Should remove multiple consecutive newlines before tags")
    void testRemoveMultipleNewlinesBeforeTags() {
        String input = "Text\n\n\n<p>Content</p>";
        String result = HtmlFormatter.cleanHtml(input);
        // Multiple newlines before tag should be removed
        assertEquals("Text<p>Content</p>", result);
    }

    @Test
    @DisplayName("Should remove multiple consecutive newlines after closing tags when followed by opening tag")
    void testRemoveMultipleNewlinesAfterClosingTag() {
        String input = "<li>Item 1</li>\n\n\n<li>Item 2</li>";
        String result = HtmlFormatter.cleanHtml(input);
        // Multiple newlines between tags should be removed
        assertEquals("<li>Item 1</li><li>Item 2</li>", result);
    }

    @Test
    @DisplayName("Should collapse excessive consecutive newlines in content to double newlines")
    void testCollapseExcessiveNewlines() {
        String input = "Line 1\n\n\n\n\nLine 2";
        String result = HtmlFormatter.cleanHtml(input);
        // Should collapse to 2 newlines (paragraph break)
        assertEquals("Line 1\n\nLine 2", result);
    }

    @Test
    @DisplayName("Should handle paragraph tags correctly")
    void testHandleParagraphTags() {
        String input = "<p>First paragraph</p>\n\n<p>Second paragraph</p>";
        String result = HtmlFormatter.cleanHtml(input);
        // Should not have newlines around tags
        assertFalse(result.startsWith("\n"));
        assertFalse(result.contains(">\n\n<"));
    }

    @Test
    @DisplayName("Should handle strong and em tags")
    void testHandleStrongAndEmTags() {
        String input = "Text with\n<strong>bold</strong>\nand\n<em>italic</em>\ntext";
        String result = HtmlFormatter.cleanHtml(input);
        // Newlines before tags should be removed
        assertFalse(result.contains("\n<strong>"));
        assertFalse(result.contains("\n<em>"));
        // Newlines before closing tags should be removed
        assertFalse(result.contains("bold</strong>\nand\n<em>"));
        // Newlines after closing tags but before text should be preserved
        assertTrue(result.contains("</strong>\nand"));
        assertTrue(result.contains("</em>\ntext"));
    }

    @Test
    @DisplayName("Should handle anchor tags")
    void testHandleAnchorTags() {
        String input = "Click\n<a href=\"#\">here</a>\nto continue";
        String result = HtmlFormatter.cleanHtml(input);
        // Newlines immediately before opening tags should be removed
        assertFalse(result.contains("Click\n<a"));
        // Newlines after closing tags but before text should be preserved
        assertTrue(result.contains("</a>\nto"));
    }

    @Test
    @DisplayName("Should handle nested tags")
    void testHandleNestedTags() {
        String input = "<ul>\n<li>\n<strong>Bold item</strong>\n</li>\n</ul>";
        String result = HtmlFormatter.cleanHtml(input);
        // Should clean all newlines around tags
        assertEquals("<ul><li><strong>Bold item</strong></li></ul>", result);
    }

    @Test
    @DisplayName("Should preserve HTML entities and special characters")
    void testPreserveHtmlEntitiesAndSpecialCharacters() {
        String input = "<p>Text with &nbsp; entity and special chars: &lt;, &gt;</p>";
        String result = HtmlFormatter.cleanHtml(input);
        assertTrue(result.contains("&nbsp;"));
        assertTrue(result.contains("&lt;"));
        assertTrue(result.contains("&gt;"));
    }

    @Test
    @DisplayName("Should handle self-closing tags")
    void testHandleSelfClosingTags() {
        String input = "Text\n<br>\nMore text";
        String result = HtmlFormatter.cleanHtml(input);
        // Newlines before self-closing tags should be removed
        assertFalse(result.contains("Text\n<br"));
        // Newlines after self-closing tags but before text should be preserved
        assertTrue(result.contains("<br>\nMore"));
    }

    @Test
    @DisplayName("Should handle div tags")
    void testHandleDivTags() {
        String input = "<div>\n<p>Content</p>\n</div>";
        String result = HtmlFormatter.cleanHtml(input);
        assertEquals("<div><p>Content</p></div>", result);
    }

    @Test
    @DisplayName("Example test case 1: List with newlines")
    void testExampleListWithNewlines() {
        String input = "\n<ul>\n<li>Item 1</li>\n<li>Item 2</li>\n</ul>";
        String expected = "<ul><li>Item 1</li><li>Item 2</li></ul>";
        String result = HtmlFormatter.cleanHtml(input);
        assertEquals(expected, result, "Example test case 1 failed");
    }

    @Test
    @DisplayName("Example test case 2: Preserve paragraph breaks")
    void testExamplePreserveParagraphBreaks() {
        String input = "Paragraph 1\n\nParagraph 2\n<p>Text</p>";
        String result = HtmlFormatter.cleanHtml(input);
        // The meaningful double newline should be preserved, but newline before <p>
        // removed
        assertTrue(result.startsWith("Paragraph 1\n\nParagraph 2"));
        assertFalse(result.contains("Paragraph 2\n<p>"));
    }

    @Test
    @DisplayName("Should handle complex real-world HTML structure")
    void testComplexRealWorldHtmlStructure() {
        String input = "<div class=\"complaint\">\n" +
                "  <h2>Complaint Title</h2>\n" +
                "  <p>First paragraph with some text.</p>\n" +
                "  <p>Second paragraph with more content.</p>\n" +
                "  <ul>\n" +
                "    <li>Item 1</li>\n" +
                "    <li>Item 2</li>\n" +
                "  </ul>\n" +
                "</div>";

        String result = HtmlFormatter.cleanHtml(input);

        // Verify no consecutive newlines before tags
        assertFalse(result.contains("\n<h2>"));
        assertFalse(result.contains("\n<p>"));
        assertFalse(result.contains("\n<ul>"));
        assertFalse(result.contains("\n<li>"));
        assertFalse(result.contains("\n</ul>"));
        assertFalse(result.contains("\n</div>"));

        // Verify structure is intact
        assertTrue(result.contains("<h2>Complaint Title</h2>"));
        assertTrue(result.contains("<p>First paragraph"));
        assertTrue(result.contains("<li>Item 1</li>"));
    }

    @Test
    @DisplayName("Should not modify HTML without excessive newlines")
    void testNoModificationForCleanHtml() {
        String input = "<p>Clean HTML</p><p>No newlines</p>";
        String result = HtmlFormatter.cleanHtml(input);
        assertEquals(input, result);
    }

    @Test
    @DisplayName("Should handle tags with attributes and newlines")
    void testHandleTagsWithAttributesAndNewlines() {
        String input = "Text\n<a href=\"http://example.com\" class=\"link\">Link text</a>\nMore text";
        String result = HtmlFormatter.cleanHtml(input);
        // Should preserve tag attributes but remove newlines
        assertTrue(result.contains("href=\"http://example.com\""));
        assertFalse(result.contains("\n<a"));
    }

    @Test
    @DisplayName("Should handle consecutive closing and opening tags")
    void testHandleConsecutiveClosingAndOpeningTags() {
        String input = "</p>\n\n<p>";
        String result = HtmlFormatter.cleanHtml(input);
        assertEquals("</p><p>", result);
    }
}
