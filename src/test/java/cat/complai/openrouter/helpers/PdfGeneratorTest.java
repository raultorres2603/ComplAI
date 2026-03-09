package cat.complai.openrouter.helpers;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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

            Assertions.assertTrue(extracted.contains("This is a test complaint."));
        }
    }

    @Test
    void testGeneratePdf_catalanCharacters() throws IOException {
        // Realistic Catalan complaint letter — exercises diacritics (à, é, è, ò, ú, ç, ï, l·l)
        String text = """
                Benvolgut/da Alcalde/ssa de l'Ajuntament d'El Prat de Llobregat,

                Em dirigeixo a vostès per presentar una queixa formal sobre el soroll \
                excessiu provinent de l'aeroport del Prat, que afecta greument la nostra \
                qualitat de vida i la tranquil·litat del barri.

                Sol·licito que es prenguin les mesures necessàries per reduir l'impacte \
                acústic, d'acord amb la normativa vigent.

                Atentament,
                Un veí preocupat""";

        byte[] pdfBytes = PdfGenerator.generatePdf(text);

        Assertions.assertNotNull(pdfBytes);
        Assertions.assertTrue(pdfBytes.length > 0);

        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);

            Assertions.assertTrue(extracted.contains("Estimat/da Senyor/a"), "PDF should contain greeting");
            Assertions.assertTrue(extracted.contains("Ajuntament"), "PDF should contain Ajuntament");
            Assertions.assertTrue(extracted.contains("Raul Torres"), "PDF should contain signature");
            Assertions.assertTrue(extracted.contains("necessàries"), "PDF should contain necessàries (à)");
        }
    }

    @Test
    void testGeneratePdf_emptyText() throws IOException {
        byte[] pdfBytes = PdfGenerator.generatePdf("");

        Assertions.assertNotNull(pdfBytes);
        Assertions.assertTrue(pdfBytes.length > 0);

        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);

            System.out.println("Extracted empty PDF text:\n" + extracted);

            Assertions.assertTrue(extracted.trim().isEmpty(), "PDF should be empty for empty input");
        }
    }

    @Test
    void testGeneratePdf_realisticAiResponseBody() throws IOException {
        // Simulates the parsed body after AiParsed strips the JSON header.
        // The AI typically leaves a blank line between the header and the letter body.
        // After AiParsed.trim(), the body starts directly with the letter text.
        String parsedBody = """
                Estimat/da Senyor/a,

                Per la present, em dirigeixo a l'Ajuntament d'El Prat de Llobregat per \
                comunicar la meva preocupació respecte al soroll nocturn al carrer Major.

                Atentament,
                Raul Torres""";

        byte[] pdfBytes = PdfGenerator.generatePdf(parsedBody);

        Assertions.assertNotNull(pdfBytes);
        Assertions.assertTrue(pdfBytes.length > 0);

        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String extracted = stripper.getText(doc);


            Assertions.assertTrue(extracted.contains("Estimat/da Senyor/a"), "PDF should contain greeting");
            Assertions.assertTrue(extracted.contains("Ajuntament"), "PDF should contain Ajuntament");
            Assertions.assertTrue(extracted.contains("Raul Torres"), "PDF should contain signature");
        }
    }
}

