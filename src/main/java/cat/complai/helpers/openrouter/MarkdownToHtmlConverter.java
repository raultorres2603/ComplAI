package cat.complai.helpers.openrouter;

import java.util.regex.Pattern;

/**
 * Converts Markdown formatting patterns to HTML markup.
 * Enables AI responses with Markdown formatting to be delivered as HTML to clients.
 *
 * <p>
 * Converts:
 * - **text** → <strong>text</strong> (emphasized text)
 * - *text* → <em>text</em> (italic text)
 * - [text](url) → <a href="url">text</a> (links)
 * - ## heading → <h2>heading</h2> (headings)
 * - - item → <ul><li>item</li></ul> (unordered lists)
 * - Double newlines → <p>text</p> (paragraphs)
 * - Single newlines within paragraphs → preserved or <br> as needed
 *
 * <p>
 * Order of conversion is important: convert links first (to avoid breaking URLs),
 * then block elements (paragraphs, headings, lists), then inline formatting.
 */
public class MarkdownToHtmlConverter {

    /**
     * Regex to match Markdown bold text: **text**
     */
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*([^*]+)\\*\\*");

    /**
     * Regex to match Markdown italic text: *text* (but not **)
     * Uses negative lookbehind/lookahead to avoid matching ** patterns
     */
    private static final Pattern ITALIC_PATTERN = Pattern.compile("\\*([^*]+?)\\*");

    /**
     * Regex to match Markdown links: [text](url)
     */
    private static final Pattern LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

    /**
     * Regex to match Markdown headings: ## text or ### text, etc.
     */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    /**
     * Regex to match Markdown unordered lists: - item
     */
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("^\\s*-\\s+(.+)$", Pattern.MULTILINE);

    /**
     * Converts Markdown-formatted text to HTML.
     *
     * @param markdown The Markdown-formatted text (can be null or empty)
     * @return HTML-formatted text, or null if input is null
     */
    public static String convertMarkdownToHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }

        String html = markdown;

        // Step 1: Convert links first (to avoid breaking URLs in subsequent replacements)
        html = convertLinks(html);

        // Step 2: Convert headings
        html = convertHeadings(html);

        // Step 3: Convert lists
        html = convertLists(html);

        // Step 4: Convert paragraphs (double newlines)
        html = convertParagraphs(html);

        // Step 5: Convert inline formatting (bold and italic)
        html = convertBold(html);
        html = convertItalic(html);

        // Step 6: Clean up excessive HTML formatting
        html = HtmlFormatter.cleanHtml(html);

        return html;
    }

    /**
     * Converts Markdown links [text](url) to HTML <a> tags.
     */
    private static String convertLinks(String text) {
        return LINK_PATTERN.matcher(text).replaceAll("<a href=\"$2\">$1</a>");
    }

    /**
     * Converts Markdown headings (# heading, ## heading, etc.) to HTML heading tags.
     */
    private static String convertHeadings(String text) {
        return HEADING_PATTERN.matcher(text).replaceAll(matchResult -> {
            String hashes = matchResult.group(1);
            String headingText = matchResult.group(2);
            int level = hashes.length();
            return "<h" + level + ">" + headingText + "</h" + level + ">";
        });
    }

    /**
     * Converts Markdown unordered lists (- item) to HTML <ul><li> tags.
     * Groups consecutive list items into a single <ul> block.
     */
    private static String convertLists(String text) {
        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\n", -1); // -1 to preserve trailing empty strings
        boolean inList = false;

        for (String line : lines) {
            if (LIST_ITEM_PATTERN.matcher(line).find()) {
                if (!inList) {
                    result.append("<ul>\n");
                    inList = true;
                }
                String itemText = line.replaceAll("^\\s*-\\s+", "");
                result.append("<li>").append(itemText).append("</li>\n");
            } else {
                if (inList) {
                    result.append("</ul>\n");
                    inList = false;
                }
                result.append(line).append("\n");
            }
        }

        if (inList) {
            result.append("</ul>\n");
        }

        // Remove trailing newline added by split
        String resultStr = result.toString();
        if (resultStr.endsWith("\n")) {
            resultStr = resultStr.substring(0, resultStr.length() - 1);
        }

        return resultStr;
    }

    /**
     * Converts paragraph breaks (double newlines) to HTML <p> tags.
     * Single newlines within paragraphs are converted to <br> tags.
     */
    private static String convertParagraphs(String text) {
        // Split by double newlines to identify paragraph boundaries
        String[] paragraphs = text.split("\n\n+");
        StringBuilder result = new StringBuilder();

        for (String para : paragraphs) {
            if (para.trim().isEmpty()) {
                continue;
            }

            // Skip if paragraph is already wrapped in tags (heading, list, etc.)
            if (para.trim().startsWith("<")) {
                result.append(para);
                if (!para.endsWith("\n")) {
                    result.append("\n");
                }
            } else {
                // Convert single newlines within the paragraph to <br> tags
                String paraWithBr = para.replaceAll("\n", "<br>");
                result.append("<p>").append(paraWithBr).append("</p>\n");
            }
        }

        return result.toString().trim();
    }

    /**
     * Converts Markdown bold text **text** to HTML <strong> tags.
     */
    private static String convertBold(String text) {
        return BOLD_PATTERN.matcher(text).replaceAll("<strong>$1</strong>");
    }

    /**
     * Converts Markdown italic text *text* to HTML <em> tags.
     * Uses a cautious approach to avoid converting ** patterns.
     */
    private static String convertItalic(String text) {
        // Only convert *text* if not preceded/followed by another *
        // This prevents converting ** which should already be handled by bold conversion
        return ITALIC_PATTERN.matcher(text).replaceAll("<em>$1</em>");
    }
}
