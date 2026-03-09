package cat.complai.openrouter.helpers;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class PdfGenerator {

    private static final float MARGIN = 50f;
    private static final float FONT_SIZE = 12f;
    private static final float LINE_HEIGHT = 15f;
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float USABLE_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final float BOTTOM_MARGIN = MARGIN;

    public static byte[] generatePdf(String content) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDType0Font customFont = loadFont(document);

            // Split on newlines first, then word-wrap each paragraph to fit the page width.
            List<String> visualLines = buildVisualLines(content, customFont);

            PDPage currentPage = addNewPage(document);
            PDPageContentStream contentStream = openContentStream(document, currentPage, customFont);
            float currentY = PAGE_HEIGHT - MARGIN;

            for (String line : visualLines) {
                // Start a new page when there is no room for the next line
                if (currentY - LINE_HEIGHT < BOTTOM_MARGIN) {
                    contentStream.endText();
                    contentStream.close();
                    currentPage = addNewPage(document);
                    contentStream = openContentStream(document, currentPage, customFont);
                    currentY = PAGE_HEIGHT - MARGIN;
                }

                if (line.isEmpty()) {
                    contentStream.newLineAtOffset(0, -LINE_HEIGHT);
                } else {
                    contentStream.showText(line);
                    contentStream.newLineAtOffset(0, -LINE_HEIGHT);
                }
                currentY -= LINE_HEIGHT;
            }

            contentStream.endText();
            contentStream.close();

            document.save(baos);
            return baos.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Error generating PDF with Apache PDFBox", e);
        }
    }

    /**
     * Splits {@code content} into visual lines that fit within {@link #USABLE_WIDTH}.
     * Each '\n'-delimited paragraph is word-wrapped independently. An empty paragraph
     * produces a single empty line (blank-line spacing).
     */
    private static List<String> buildVisualLines(String content, PDType0Font customFont) throws Exception {
        String[] paragraphs = content != null ? content.split("\n") : new String[]{""};
        List<String> result = new ArrayList<>();
        for (String raw : paragraphs) {
            String paragraph = raw.replace("\r", "").trim();
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }
            result.addAll(wrapParagraph(paragraph, customFont));
        }
        return result;
    }

    /**
     * Word-wraps a single non-empty paragraph into lines that fit within {@link #USABLE_WIDTH}.
     * Falls back to character-level splitting when a single word is wider than the page.
     */
    private static List<String> wrapParagraph(String paragraph, PDType0Font customFont) throws Exception {
        String[] words = paragraph.split(" ");
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (measureTextWidth(candidate, customFont) <= USABLE_WIDTH) {
                current = new StringBuilder(candidate);
            } else {
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    // Single word wider than usable width: force a break mid-word
                    lines.addAll(splitOversizedWord(word, customFont));
                }
            }
        }

        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    /**
     * Splits a single word that is wider than {@link #USABLE_WIDTH} by characters.
     * This is a safety net; in practice it should only happen with extreme inputs.
     */
    private static List<String> splitOversizedWord(String word, PDType0Font customFont) throws Exception {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char ch : word.toCharArray()) {
            String candidate = current.toString() + ch;
            if (measureTextWidth(candidate, customFont) <= USABLE_WIDTH) {
                current.append(ch);
            } else {
                if (!current.isEmpty()) chunks.add(current.toString());
                current = new StringBuilder(String.valueOf(ch));
            }
        }
        if (!current.isEmpty()) chunks.add(current.toString());
        return chunks;
    }

    private static float measureTextWidth(String text, PDType0Font customFont) throws Exception {
        if (customFont != null) {
            return customFont.getStringWidth(text) / 1000f * FONT_SIZE;
        }
        // PDType1Font.HELVETICA width measurement
        return PDType1Font.HELVETICA.getStringWidth(text) / 1000f * FONT_SIZE;
    }

    private static PDPage addNewPage(PDDocument document) {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        return page;
    }

    /** Opens a content stream for the given page and positions the cursor at the top margin. */
    private static PDPageContentStream openContentStream(
            PDDocument document, PDPage page, PDType0Font customFont) throws Exception {
        PDPageContentStream stream = new PDPageContentStream(document, page);
        stream.beginText();
        if (customFont != null) {
            stream.setFont(customFont, FONT_SIZE);
        } else {
            stream.setFont(PDType1Font.HELVETICA, FONT_SIZE);
        }
        stream.newLineAtOffset(MARGIN, PAGE_HEIGHT - MARGIN);
        return stream;
    }

    private static PDType0Font loadFont(PDDocument document) {
        try (InputStream fontStream = PdfGenerator.class.getResourceAsStream("/NotoSans-Regular.ttf")) {
            if (fontStream == null) {
                System.out.println("Warning: Custom font not found. Falling back to default.");
                return null;
            }
            return PDType0Font.load(document, fontStream);
        } catch (Exception e) {
            System.out.println("Warning: Could not load font: " + e.getMessage());
            return null;
        }
    }
}