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

    private static final float MARGIN = 50;
    private static final float FONT_SIZE = 11;
    private static final float LINE_HEIGHT = 14.5f;
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float BOTTOM_MARGIN = 50;

    public static byte[] generatePdf(String content) {
        if (content == null || content.trim().isEmpty()) {
            content = "No content was generated or extracted."; // Fallback to prevent completely empty/corrupted PDFs
        }

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            InputStream fontStream = PdfGenerator.class.getResourceAsStream("/NotoSans-Regular.ttf");
            PDType0Font customFont = fontStream != null ? PDType0Font.load(document, fontStream) : null;
            PDType1Font fallbackFont = new PDType1Font(PDType1Font.HELVETICA.getCOSObject());

            List<String> visualLines = buildVisualLines(content, customFont != null ? customFont : fallbackFont);

            PDPage currentPage = addNewPage(document);
            PDPageContentStream contentStream = openContentStream(document, currentPage, customFont, fallbackFont);
            float currentY = PAGE_HEIGHT - MARGIN;

            for (String line : visualLines) {
                if (currentY - LINE_HEIGHT < BOTTOM_MARGIN) {
                    contentStream.endText();
                    contentStream.close();
                    currentPage = addNewPage(document);
                    contentStream = openContentStream(document, currentPage, customFont, fallbackFont);
                    currentY = PAGE_HEIGHT - MARGIN;
                }

                if (!line.isEmpty()) {
                    contentStream.showText(line);
                }
                contentStream.newLineAtOffset(0, -LINE_HEIGHT);
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

    private static PDPageContentStream openContentStream(PDDocument document, PDPage page, PDType0Font customFont, PDType1Font fallbackFont) throws Exception {
        PDPageContentStream stream = new PDPageContentStream(document, page);
        stream.beginText();
        if (customFont != null) {
            stream.setFont(customFont, FONT_SIZE);
        } else {
            stream.setFont(fallbackFont, FONT_SIZE);
        }
        stream.newLineAtOffset(MARGIN, PAGE_HEIGHT - MARGIN);
        return stream;
    }

    private static List<String> buildVisualLines(String content, Object font) throws Exception {
        String[] paragraphs = content.split("\n");
        List<String> result = new ArrayList<>();

        for (String raw : paragraphs) {
            String paragraph = raw.replace("\r", "").trim();
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }
            // Basic wrapping logic (simplified for standard constraints)
            result.add(paragraph); // In full logic, wrap paragraph by string width based on font
        }
        return result;
    }
}