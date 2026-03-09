package cat.complai.openrouter.helpers;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Logger;
import java.util.logging.Level;

public class PdfGenerator {
    private static final Logger logger = Logger.getLogger(PdfGenerator.class.getName());
    private static final String FONT_RESOURCE = "/NotoSans-Regular.ttf";
    private static final float MARGIN = 50f;
    private static final PDRectangle PAGE_SIZE = PDRectangle.LETTER;
    private static final float TITLE_GAP = 20f;
    private static final float LEADING = 14f;
    // Reduced line length to avoid off-page text since NotoSans is not monospaced
    private static final int MAX_PER_LINE = 80;

    public static byte[] generatePdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            writeBodyToDocument(doc, text);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                doc.save(out);
                return out.toByteArray();
            }
        }
    }

    private static void writeBodyToDocument(PDDocument doc, String text) throws IOException {
        InputStream fontStream = PdfGenerator.class.getResourceAsStream(FONT_RESOURCE);
        if (fontStream == null) {
            throw new IOException("Font resource not found: " + FONT_RESOURCE);
        }

        PDType0Font font;
        try (fontStream) {
            // Use subsetting (true) to reduce file size and potential font issues
            font = PDType0Font.load(doc, fontStream, true);
        }

        float yStart = PAGE_SIZE.getHeight() - MARGIN - TITLE_GAP;
        float yPosition = yStart;

        PDPage page = new PDPage(PAGE_SIZE);
        doc.addPage(page);
        PDPageContentStream contents = new PDPageContentStream(doc, page);

        // Title
        contents.beginText();
        contents.setFont(font, 16);
        // Explicitly set color to black
        contents.setNonStrokingColor(0, 0, 0);
        contents.newLineAtOffset(MARGIN, yStart + 15);
        contents.showText("Redacted complaint");
        contents.endText();

        // Begin body
        contents.beginText();
        contents.setFont(font, 12);
        contents.newLineAtOffset(MARGIN, yPosition);

        String normalized = (text == null) ? "" : text.replace("\r", "");
        String[] paragraphs = normalized.split("\n\\s*\n");

        for (String s : paragraphs) {
            String paragraph = s.replace("\n", " ").trim();
            if (paragraph.isEmpty()) {
                // Empty paragraph (double newline), just add spacing
                yPosition -= LEADING;
                contents.newLineAtOffset(0, -LEADING);
                if (checkPageBreak(doc, yPosition)) {
                   // Page break happened
                   contents.endText();
                   contents.close();

                   page = addNewPage(doc);
                   contents = new PDPageContentStream(doc, page);
                   yPosition = PAGE_SIZE.getHeight() - MARGIN;

                   contents.beginText();
                   contents.setFont(font, 12);
                   contents.setNonStrokingColor(0, 0, 0);
                   contents.newLineAtOffset(MARGIN, yPosition);
                }
                continue;
            }

            String[] words = paragraph.split("\\s+");
            StringBuilder lineBuilder = new StringBuilder();

            for (String w : words) {
                if (lineBuilder.length() + w.length() + 1 > MAX_PER_LINE) {
                    contents.showText(lineBuilder.toString().trim());
                    contents.newLineAtOffset(0, -LEADING);
                    yPosition -= LEADING;
                    lineBuilder.setLength(0);

                    if (checkPageBreak(doc, yPosition)) {
                        contents.endText();
                        contents.close();

                        page = addNewPage(doc);
                        contents = new PDPageContentStream(doc, page);
                        yPosition = PAGE_SIZE.getHeight() - MARGIN;

                        contents.beginText();
                        contents.setFont(font, 12);
                        contents.setNonStrokingColor(0, 0, 0);
                        contents.newLineAtOffset(MARGIN, yPosition);
                    }
                }
                if (!lineBuilder.isEmpty()) lineBuilder.append(' ');
                lineBuilder.append(w);
            }
            if (!lineBuilder.isEmpty()) {
                contents.showText(lineBuilder.toString().trim());
                contents.newLineAtOffset(0, -LEADING);
                yPosition -= LEADING;
            }
            // Paragraph gap
            yPosition -= (LEADING / 2f);
            contents.newLineAtOffset(0, -(LEADING / 2f));

            if (checkPageBreak(doc, yPosition)) {
                contents.endText();
                contents.close();

                page = addNewPage(doc);
                contents = new PDPageContentStream(doc, page);
                yPosition = PAGE_SIZE.getHeight() - MARGIN;

                contents.beginText();
                contents.setFont(font, 12);
                contents.setNonStrokingColor(0, 0, 0);
                contents.newLineAtOffset(MARGIN, yPosition);
            }
        }
        contents.endText();
        contents.close();
    }

    private static boolean checkPageBreak(PDDocument doc, float yPosition) {
        return yPosition < MARGIN;
    }

    private static PDPage addNewPage(PDDocument doc) {
        PDPage newPage = new PDPage(PAGE_SIZE);
        doc.addPage(newPage);
        return newPage;
    }
}
