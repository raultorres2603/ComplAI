package cat.complai.openrouter.helpers;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

class PdfGeneratorTest {

    @Test
    void testGeneratePdf() throws IOException {
        String text = "This is a test complaint.\nIt has multiple lines.\nAnd even more lines.";
        byte[] pdfBytes = PdfGenerator.generatePdf(text);

        Assertions.assertNotNull(pdfBytes);
        Assertions.assertTrue(pdfBytes.length > 0);

        // Load document and extract text to verify content
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);

            System.out.println("Extracted text:\n" + extracted);

            Assertions.assertTrue(extracted.contains("Redacted complaint"));
            Assertions.assertTrue(extracted.contains("This is a test complaint."));
        }

        // Save to file for manual inspection if needed (optional)
        // try (FileOutputStream fos = new FileOutputStream("test_output.pdf")) {
        //     fos.write(pdfBytes);
        // }
    }
}

