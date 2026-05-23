package cat.complai.helpers.openrouter;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Generates a PDF document from a plain-text complaint letter.
 *
 * <p>
 * Uses OpenPDF to lay out the text on A4 pages with automatic word-wrap and
 * page breaks. Prefers the {@code NotoSans-Regular.ttf} font bundled on the
 * classpath; falls back to {@code Helvetica} when the font resource is absent.
 *
 * <p>
 * OpenPDF replaces Apache PDFBox because PDFBox requires the AWT native library
 * ({@code libawt}), which is unavailable in the GraalVM native-image environment
 * used by the AWS Lambda worker. OpenPDF has zero AWT dependency and works
 * reliably in native-image.
 */
public class PdfGenerator {

    private static final float MARGIN = 50;
    private static final float FONT_SIZE = 11;
    private static final String FONT_ALIAS = "NotoSans";

    /**
     * Generates a PDF from the given plain-text content and returns it as a byte
     * array.
     *
     * @param content the plain-text letter body to render; a default placeholder
     *                is used when blank
     * @return the PDF bytes
     * @throws RuntimeException if OpenPDF encounters an error during document
     *                          generation
     */
    public static byte[] generatePdf(String content) {
        if (content == null || content.trim().isEmpty()) {
            content = "No content was generated or extracted.";
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, MARGIN, MARGIN, MARGIN, MARGIN);

        try {
            PdfWriter.getInstance(document, baos);
            document.open();

            Font font = loadFont();

            String[] paragraphs = content.split("\n");
            for (String para : paragraphs) {
                String trimmed = para.replace("\r", "").trim();
                if (trimmed.isEmpty()) {
                    // Preserve paragraph spacing — a space-only paragraph keeps the
                    // vertical gap between blocks, just as the original PDFBox code did
                    // by inserting a blank visual line.
                    document.add(new Paragraph(" ", font));
                } else {
                    document.add(new Paragraph(trimmed, font));
                }
            }

            document.close();
        } catch (DocumentException | IOException e) {
            throw new RuntimeException("Error generating PDF with OpenPDF", e);
        }

        return baos.toByteArray();
    }

    /**
     * Loads the preferred NotoSans font (from classpath) or falls back to
     * Helvetica.
     *
     * <p>
     * OpenPDF's {@link FontFactory#register(String, String)} requires a file path,
     * so the classpath resource is extracted to a temporary file for registration.
     * The temp file is marked for deletion on JVM exit.
     */
    private static Font loadFont() throws IOException, DocumentException {
        try (InputStream fontStream = PdfGenerator.class.getResourceAsStream("/NotoSans-Regular.ttf")) {
            if (fontStream != null) {
                Path tempFile = Files.createTempFile("NotoSans", ".ttf");
                try {
                    Files.copy(fontStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                    FontFactory.register(tempFile.toString(), FONT_ALIAS);
                } finally {
                    // The font data is loaded into memory by FontFactory, so the temp
                    // file is no longer needed after registration.
                    Files.deleteIfExists(tempFile);
                }
            }
        }

        if (FontFactory.isRegistered(FONT_ALIAS)) {
            return FontFactory.getFont(FONT_ALIAS, FONT_SIZE);
        }
        return FontFactory.getFont(FontFactory.HELVETICA, FONT_SIZE);
    }
}
