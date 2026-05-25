package cat.complai.helpers.openrouter;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfGeneratorTest {

    @Test
    void testGeneratePdf() throws IOException {
        String text = "This is a test complaint.\nIt has multiple lines.\nAnd even more lines.";
        byte[] pdfBytes = PdfGenerator.generatePdf(text);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0);

        // Load document and extract text to verify content
        try (PdfReader reader = new PdfReader(pdfBytes)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                sb.append(extractor.getTextFromPage(i));
            }
            String extracted = sb.toString();

            System.out.println("Extracted text:\n" + extracted);

            assertTrue(extracted.contains("This is a test complaint."),
                    "PDF should contain the complaint text");
        }
    }

    @Test
    void testGeneratePdf_catalanCharacters() throws IOException {
        // Realistic Catalan complaint letter — exercises diacritics
        // (à, é, è, ò, ú, ç, ï, l·l)
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

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0);

        try (PdfReader reader = new PdfReader(pdfBytes)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                sb.append(extractor.getTextFromPage(i));
            }
            String extracted = sb.toString();

            assertTrue(extracted.contains("Benvolgut/da"), "PDF should contain greeting");
            assertTrue(extracted.contains("Ajuntament"), "PDF should contain Ajuntament");
            assertTrue(extracted.contains("Un veí preocupat"), "PDF should contain sign-off");
            assertTrue(extracted.contains("necessàries"), "PDF should contain necessàries (à)");
        }
    }

    @Test
    void testGeneratePdf_realisticAiResponseBody() throws IOException {
        // Simulates the parsed body after AiParsed strips the JSON header.
        // The AI typically leaves a blank line between the header and the letter
        // body.
        // After AiParsed.trim(), the body starts directly with the letter text.
        String parsedBody = """
                Estimat/da Senyor/a,

                Per la present, em dirigeixo a l'Ajuntament d'El Prat de Llobregat per \
                comunicar la meva preocupació respecte al soroll nocturn al carrer Major.

                Atentament,
                Raul Torres""";

        byte[] pdfBytes = PdfGenerator.generatePdf(parsedBody);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0);

        try (PdfReader reader = new PdfReader(pdfBytes)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= reader.getNumberOfPages(); i++) {
                sb.append(extractor.getTextFromPage(i));
            }
            String extracted = sb.toString();

            assertTrue(extracted.contains("Estimat/da Senyor/a"), "PDF should contain greeting");
            assertTrue(extracted.contains("Ajuntament"), "PDF should contain Ajuntament");
            assertTrue(extracted.contains("Raul Torres"), "PDF should contain signature");
        }
    }
}
