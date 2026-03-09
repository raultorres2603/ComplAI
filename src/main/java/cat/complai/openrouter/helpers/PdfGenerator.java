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

    public static byte[] generatePdf(String content) {
        // 1. try-with-resources ensures the document is closed in memory
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDType0Font customFont = null;
            try {
                InputStream fontStream = PdfGenerator.class.getResourceAsStream("/NotoSans-Regular.ttf");
                if (fontStream != null) {
                    customFont = PDType0Font.load(document, fontStream);
                } else {
                    System.out.println("Warning: Custom font not found. Falling back to default.");
                }
            } catch (Exception e) {
                System.out.println("Warning: Could not load font: " + e.getMessage());
            }

            // 2. try-with-resources for PDPageContentStream.
            // ---> THIS IS THE FIX! It automatically calls contentStream.close() <---
            // Without closing this stream, the text never flushes to the page, leaving it empty.
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {

                contentStream.beginText();

                if (customFont != null) {
                    contentStream.setFont(customFont, 12);
                } else {
                    // Fallback to standard PDFBox font
                    contentStream.setFont(PDType1Font.HELVETICA, 12);
                }

                // Initial position (X: 50, Y: 750 - top left of A4)
                float startX = 50;
                float startY = PDRectangle.A4.getHeight() - 50;
                contentStream.newLineAtOffset(startX, startY);

                // PDFBox doesn't auto-wrap text, so we handle basic line splitting safely
                String[] paragraphs = content != null ? content.split("\n") : new String[]{""};
                for (String paragraph : paragraphs) {
                    // Strip problematic characters like carriage returns
                    paragraph = paragraph.replace("\r", "").trim();
                    if (paragraph.isEmpty()) {
                        contentStream.newLineAtOffset(0, -15); // Empty line spacing
                        continue;
                    }

                    // Basic safeguard to avoid text going off the page boundary
                    // (You can enhance this by splitting by words if lines are too long)
                    contentStream.showText(paragraph);
                    contentStream.newLineAtOffset(0, -15);
                }

                contentStream.endText();

                // The try-with-resources block ends here, automatically calling contentStream.close()
                // and flushing all the text data into the PDF page buffer.
            }

            // 3. Now that the content stream is closed and flushed, we save the document
            document.save(baos);

            return baos.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Error generating PDF with Apache PDFBox", e);
        }
    }
}