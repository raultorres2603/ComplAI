package cat.complai.openrouter.helpers;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates a PDF document from a plain-text complaint letter.
 *
 * <p>
 * Uses Apache PDFBox to lay out the text on A4 pages with word-wrap and
 * automatic
 * page breaks. Prefers the {@code NotoSans-Regular.ttf} font bundled on the
 * classpath;
 * falls back to {@code Helvetica} when the font resource is absent.
 */
public class PdfGenerator {

    private static final float MARGIN = 50;
    private static final float FONT_SIZE = 11;
    private static final float LINE_HEIGHT = 14.5f;
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float USABLE_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final float BOTTOM_MARGIN = 50;

    /**
     * Generates a PDF from the given plain-text content and returns it as a byte
     * array.
     *
     * @param content the plain-text letter body to render; a default placeholder is
     *                used when blank
     * @return the PDF bytes
     * @throws RuntimeException if PDFBox encounters an error during document
     *                          generation
     */
    public static byte[] generatePdf(String content) {
        if (content == null || content.trim().isEmpty()) {
            content = "No content was generated or extracted.";
        }

        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            InputStream fontStream = PdfGenerator.class.getResourceAsStream("/NotoSans-Regular.ttf");
            // Bug fix: PDType1Font.HELVETICA is the correct static constant to reuse.
            // Constructing new PDType1Font(PDType1Font.HELVETICA.getCOSObject()) shares the
            // same
            // mutable COS dictionary, which can corrupt font encoding and produce a blank
            // PDF.
            PDFont font = fontStream != null ? PDType0Font.load(document, fontStream) : PDType1Font.HELVETICA;

            List<String> visualLines = buildVisualLines(content, font);

            PDPage currentPage = addNewPage(document);
            PDPageContentStream contentStream = openContentStream(document, currentPage, font);
            float currentY = PAGE_HEIGHT - MARGIN;

            for (String line : visualLines) {
                if (currentY - LINE_HEIGHT < BOTTOM_MARGIN) {
                    contentStream.endText();
                    contentStream.close();
                    currentPage = addNewPage(document);
                    contentStream = openContentStream(document, currentPage, font);
                    currentY = PAGE_HEIGHT - MARGIN;
                }

                if (!line.isEmpty()) {
                    contentStream.showText(line);
                }
                // newLine() advances by LINE_HEIGHT (set via setLeading in openContentStream).
                // This must be called for every line — including empty ones — so that the text
                // matrix position stays in sync with currentY. Using newLineAtOffset(0, -n)
                // only
                // for non-empty lines caused the cursor to drift off-page, producing a blank
                // PDF.
                contentStream.newLine();
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

    private static PDPage addNewPage(PDDocument document) {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        return page;
    }

    private static PDPageContentStream openContentStream(PDDocument document, PDPage page, PDFont font)
            throws Exception {
        PDPageContentStream stream = new PDPageContentStream(document, page);
        stream.beginText();
        stream.setFont(font, FONT_SIZE);
        // setLeading tells PDFBox how far to advance per newLine() call.
        // Without this, newLine() moves by zero and all lines are stacked on top of
        // each other.
        stream.setLeading(LINE_HEIGHT);
        stream.newLineAtOffset(MARGIN, PAGE_HEIGHT - MARGIN);
        return stream;
    }

    /**
     * Splits content into visual lines that fit within USABLE_WIDTH.
     * Each paragraph from the input is word-wrapped independently.
     * Without this, lines longer than the page width are rendered off-page
     * and the PDF appears blank.
     */
    private static List<String> buildVisualLines(String content, PDFont font) throws Exception {
        String[] paragraphs = content.split("\n");
        List<String> result = new ArrayList<>();

        for (String raw : paragraphs) {
            String paragraph = raw.replace("\r", "").trim();
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }
            result.addAll(wrapParagraph(paragraph, font));
        }
        return result;
    }

    /**
     * Word-wraps a single paragraph into lines that fit within USABLE_WIDTH at
     * FONT_SIZE.
     * PDFBox's getStringWidth() returns width in 1/1000 text space units; divide by
     * 1000
     * and multiply by font size to get points.
     */
    private static List<String> wrapParagraph(String paragraph, PDFont font) throws Exception {
        String[] words = paragraph.split(" ");
        List<String> lines = new ArrayList<>();
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
            float widthPt = font.getStringWidth(candidate) / 1000f * FONT_SIZE;
            if (widthPt > USABLE_WIDTH && !currentLine.isEmpty()) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            } else {
                currentLine = new StringBuilder(candidate);
            }
        }
        if (!currentLine.isEmpty()) {
            lines.add(currentLine.toString());
        }
        return lines;
    }
}
