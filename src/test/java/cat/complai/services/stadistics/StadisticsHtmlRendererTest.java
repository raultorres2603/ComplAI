package cat.complai.services.stadistics;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import cat.complai.services.stadistics.models.ComplaintFile;
import cat.complai.services.stadistics.models.FeedbackFile;
import cat.complai.services.stadistics.models.StadisticsModel;
import cat.complai.services.stadistics.models.StadisticsModel.MonthlyData;

@DisplayName("StadisticsHtmlRenderer Tests")
class StadisticsHtmlRendererTest {

    private final StadisticsHtmlRenderer renderer = new StadisticsHtmlRenderer();
    private final Instant now = Instant.parse("2026-05-13T10:00:00Z");

    @Nested
    @DisplayName("Full model with data")
    class FullModelTests {

        @Test
        @DisplayName("Should render KPI cards, month labels, files, prediction and footer")
        void testFullModel() throws MalformedURLException {
            var complaintFiles = new ArrayList<ComplaintFile>();
            complaintFiles.add(new ComplaintFile("reclamacio-gen.pdf", new URL("https://s3.example.com/reclamacio-gen.pdf")));
            complaintFiles.add(new ComplaintFile("reclamacio-feb.pdf", new URL("https://s3.example.com/reclamacio-feb.pdf")));

            var feedbackFiles = new ArrayList<FeedbackFile>();
            feedbackFiles.add(new FeedbackFile("feedback-gen.txt", new URL("https://s3.example.com/feedback-gen.txt")));

            var jan = new MonthlyData("Gener 2026", 50, 10, 5, new ArrayList<>(), new ArrayList<>());
            var feb = new MonthlyData("Febrer 2026", 60, 12, 7, new ArrayList<>(), new ArrayList<>());
            var mar = new MonthlyData("Març 2026", 70, 15, 9, complaintFiles, feedbackFiles);

            var yearly = new ArrayList<MonthlyData>();
            yearly.add(jan);
            yearly.add(feb);
            yearly.add(mar);

            var model = new StadisticsModel(180, 37, 21);
            model.setYearlyData(yearly);
            model.setCurrentMonth(mar);
            model.setPreviousMonth(feb);
            model.setPrediction("<p>Previsió per al proper mes: <strong>estable</strong></p>");

            String html = renderer.render(model, now, model.getPrediction());

            assertTrue(html.contains("Consultes AI"));
            assertTrue(html.contains("Reclamacions"));
            assertTrue(html.contains("Valoracions"));
            assertTrue(html.contains("180"));
            assertTrue(html.contains("37"));
            assertTrue(html.contains("21"));

            assertTrue(html.contains("Gener 2026"));
            assertTrue(html.contains("Febrer 2026"));
            assertTrue(html.contains("Març 2026"));

            assertTrue(html.contains("reclamacio-gen.pdf"));
            assertTrue(html.contains("reclamacio-feb.pdf"));
            assertTrue(html.contains("feedback-gen.txt"));
            assertTrue(html.contains("Baixar"));

            assertTrue(html.contains("Previsió per al proper mes"));
            assertTrue(html.contains("Predicció i Anàlisi"));

            assertTrue(html.contains("Monthly Statistics Report"));
            assertTrue(html.contains("Informe automatitzat generat per ComplAI"));
        }
    }

    @Nested
    @DisplayName("Empty model (no yearly data)")
    class EmptyModelTests {

        @Test
        @DisplayName("Should render header, footer, and empty-file messages")
        void testEmptyModel() {
            var model = new StadisticsModel(0, 0, 0);

            String html = renderer.render(model, now, "algun text");

            assertTrue(html.contains("ComplAI"));
            assertTrue(html.contains("Informe automatitzat generat per ComplAI"));
            assertTrue(html.contains("No hi ha reclamacions"));
            assertTrue(html.contains("No hi ha valoracions"));
        }
    }

    @Nested
    @DisplayName("Null prediction")
    class NullPredictionTests {

        @Test
        @DisplayName("Should not render prediction section when prediction is null")
        void testNullPrediction() {
            var yearly = new ArrayList<MonthlyData>();
            yearly.add(new MonthlyData("Gener 2026", 10, 2, 1, new ArrayList<>(), new ArrayList<>()));
            var model = new StadisticsModel(10, 2, 1);
            model.setYearlyData(yearly);
            model.setCurrentMonth(yearly.get(0));

            String html = renderer.render(model, now, null);

            assertFalse(html.contains("Predicció i Anàlisi"));
        }

        @Test
        @DisplayName("Should not render prediction section when prediction is blank")
        void testBlankPrediction() {
            var yearly = new ArrayList<MonthlyData>();
            yearly.add(new MonthlyData("Gener 2026", 10, 2, 1, new ArrayList<>(), new ArrayList<>()));
            var model = new StadisticsModel(10, 2, 1);
            model.setYearlyData(yearly);
            model.setCurrentMonth(yearly.get(0));

            String html = renderer.render(model, now, "   ");

            assertFalse(html.contains("Predicció i Anàlisi"));
        }
    }

    @Nested
    @DisplayName("Single month")
    class SingleMonthTests {

        @Test
        @DisplayName("Should render donut chart and monthly breakdown header")
        void testSingleMonth() {
            var yearly = new ArrayList<MonthlyData>();
            yearly.add(new MonthlyData("Abril 2026", 30, 8, 4, new ArrayList<>(), new ArrayList<>()));
            var model = new StadisticsModel(30, 8, 4);
            model.setYearlyData(yearly);
            model.setCurrentMonth(yearly.get(0));

            String html = renderer.render(model, now, null);

            assertTrue(html.contains("Interaction Mix"));
            assertTrue(html.contains("Abril 2026"));
            assertTrue(html.contains("Desglossament mensual"));
        }
    }

    @Nested
    @DisplayName("Zero interactions")
    class ZeroInteractionsTests {

        @Test
        @DisplayName("Should render KPI zeros and donut with total=0")
        void testZeroInteractions() {
            var monthly = new MonthlyData("Gener 2026", 0, 0, 0, new ArrayList<>(), new ArrayList<>());
            var yearly = new ArrayList<MonthlyData>();
            yearly.add(monthly);
            var model = new StadisticsModel(0, 0, 0);
            model.setYearlyData(yearly);
            model.setCurrentMonth(monthly);

            String html = renderer.render(model, now, null);

            assertTrue(html.contains("0"));
            assertTrue(html.contains("Interaction Mix"));
        }
    }

    @Nested
    @DisplayName("Complaint files")
    class ComplaintFilesTests {

        @Test
        @DisplayName("Should render file names and download links")
        void testComplaintFiles() throws MalformedURLException {
            var complaintFiles = new ArrayList<ComplaintFile>();
            complaintFiles.add(new ComplaintFile("queixa-123.pdf", new URL("https://s3.example.com/queixa-123.pdf")));
            complaintFiles.add(new ComplaintFile("queixa-456.pdf", new URL("https://s3.example.com/queixa-456.pdf")));

            var feedbackFiles = new ArrayList<FeedbackFile>();
            feedbackFiles.add(new FeedbackFile("feedback-abc.txt", new URL("https://s3.example.com/feedback-abc.txt")));

            var monthly = new MonthlyData("Maig 2026", 10, 3, 2, complaintFiles, feedbackFiles);
            var yearly = new ArrayList<MonthlyData>();
            yearly.add(monthly);
            var model = new StadisticsModel(10, 3, 2);
            model.setYearlyData(yearly);
            model.setCurrentMonth(monthly);

            String html = renderer.render(model, now, null);

            assertTrue(html.contains("queixa-123.pdf"));
            assertTrue(html.contains("queixa-456.pdf"));
            assertTrue(html.contains("feedback-abc.txt"));
            assertTrue(html.contains("https://s3.example.com/queixa-123.pdf"));
            assertTrue(html.contains("https://s3.example.com/queixa-456.pdf"));
            assertTrue(html.contains("https://s3.example.com/feedback-abc.txt"));
            assertTrue(html.contains("Baixar"));
        }
    }
}
