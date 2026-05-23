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

            // Track whether at least one element was added. OpenPDF does not
            // auto-create a page — an empty document.close() throws
            // "the.document.has.no.pages".
            boolean hasContent = false;

            String[] paragraphs = content.split("\n");
            for (String para : paragraphs) {
                String trimmed = para.replace("\r", "").trim();
                Paragraph p = trimmed.isEmpty()
                        ? new Paragraph(" ", font)
                        : new Paragraph(trimmed, font);
                document.add(p);
                hasContent = true;
            }

            // Safety net: if the font was invalid and no content was rendered,
            // add a fallback paragraph with the built-in Helvetica.
            if (!hasContent) {
                document.add(new Paragraph("No content was generated or extracted.",
                        FontFactory.getFont(FontFactory.HELVETICA, FONT_SIZE)));
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
     * OpenPDF's {@link FontFactory#register(String, String)} stores a file path
     * reference and loads the font lazily, so the temp file must remain accessible
     * until the font is first rendered. The file is therefore marked for deletion
     * on JVM exit rather than removed immediately.
     */
    private static Font loadFont() throws IOException, DocumentException {
        try (InputStream fontStream = PdfGenerator.class.getResourceAsStream("/NotoSans-Regular.ttf")) {
            if (fontStream != null) {
                Path tempFile = Files.createTempFile("NotoSans", ".ttf");
                Files.copy(fontStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
                FontFactory.register(tempFile.toString(), FONT_ALIAS);
                // Do NOT delete immediately — FontFactory loads fonts lazily from the
                // stored path. Mark for cleanup at JVM exit instead.
                tempFile.toFile().deleteOnExit();
            }
        }

        if (FontFactory.isRegistered(FONT_ALIAS)) {
            return FontFactory.getFont(FONT_ALIAS, FONT_SIZE);
        }
        return FontFactory.getFont(FontFactory.HELVETICA, FONT_SIZE);
    }
}
