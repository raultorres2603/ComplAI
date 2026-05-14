package cat.complai.services.stadistics.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.YearMonth;
import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StadisticsModel Tests")
class StadisticsModelTest {

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create with backward-compatible 3-arg constructor")
        void shouldCreateWithThreeArgConstructor() {
            StadisticsModel model = new StadisticsModel(10, 5, 3);
            assertEquals(10, model.getTotalAskInteractions());
            assertEquals(5, model.getTotalRedactInteractions());
            assertEquals(3, model.getTotalFeedbacks());
            assertNotNull(model.getComplaintFile());
            assertNotNull(model.getFeedbackFile());
            assertTrue(model.getComplaintFile().isEmpty());
            assertTrue(model.getFeedbackFile().isEmpty());
        }

        @Test
        @DisplayName("Should create with full 5-arg constructor")
        void shouldCreateWithFiveArgConstructor() {
            ArrayList<ComplaintFile> complaints = new ArrayList<>();
            complaints.add(new ComplaintFile("c.pdf", null));
            ArrayList<FeedbackFile> feedbacks = new ArrayList<>();
            feedbacks.add(new FeedbackFile("f.json", null));

            StadisticsModel model = new StadisticsModel(10, 5, 3, complaints, feedbacks);
            assertEquals(10, model.getTotalAskInteractions());
            assertEquals(5, model.getTotalRedactInteractions());
            assertEquals(3, model.getTotalFeedbacks());
            assertEquals(1, model.getComplaintFile().size());
            assertEquals(1, model.getFeedbackFile().size());
        }

        @Test
        @DisplayName("Default constructor should set default values")
        void defaultConstructorShouldSetDefaults() {
            StadisticsModel model = new StadisticsModel(0, 0, 0);
            assertEquals(0, model.getTotalAskInteractions());
            assertEquals(0, model.getTotalRedactInteractions());
            assertEquals(0, model.getTotalFeedbacks());
        }
    }

    @Nested
    @DisplayName("Getter/Setter Tests")
    class GetterSetterTests {

        @Test
        @DisplayName("Should set and get totalAskInteractions")
        void shouldSetAndGetTotalAskInteractions() {
            StadisticsModel model = new StadisticsModel(0, 0, 0);
            model.setTotalAskInteractions(42);
            assertEquals(42, model.getTotalAskInteractions());
        }

        @Test
        @DisplayName("Should set and get totalRedactInteractions")
        void shouldSetAndGetTotalRedactInteractions() {
            StadisticsModel model = new StadisticsModel(0, 0, 0);
            model.setTotalRedactInteractions(99);
            assertEquals(99, model.getTotalRedactInteractions());
        }

        @Test
        @DisplayName("Should set and get totalFeedbacks")
        void shouldSetAndGetTotalFeedbacks() {
            StadisticsModel model = new StadisticsModel(0, 0, 0);
            model.setTotalFeedbacks(77);
            assertEquals(77, model.getTotalFeedbacks());
        }

        @Test
        @DisplayName("Should set and get complaintFile")
        void shouldSetAndGetComplaintFile() {
            StadisticsModel model = new StadisticsModel(0, 0, 0);
            ArrayList<ComplaintFile> files = new ArrayList<>();
            files.add(new ComplaintFile("c.pdf", null));
            model.setComplaintFile(files);
            assertEquals(1, model.getComplaintFile().size());
        }

        @Test
        @DisplayName("Should set and get feedbackFile")
        void shouldSetAndGetFeedbackFile() {
            StadisticsModel model = new StadisticsModel(0, 0, 0);
            ArrayList<FeedbackFile> files = new ArrayList<>();
            files.add(new FeedbackFile("f.json", null));
            model.setFeedbackFile(files);
            assertEquals(1, model.getFeedbackFile().size());
        }

        @Test
        @DisplayName("Should set and get currentMonth")
        void shouldSetAndGetCurrentMonth() {
            StadisticsModel model = new StadisticsModel(0, 0, 0);
            StadisticsModel.MonthlyData md = new StadisticsModel.MonthlyData();
            md.setMonthLabel("Gener 2026");
            model.setCurrentMonth(md);
            assertNotNull(model.getCurrentMonth());
            assertEquals("Gener 2026", model.getCurrentMonth().getMonthLabel());
        }

        @Test
        @DisplayName("Should set and get previousMonth")
        void shouldSetAndGetPreviousMonth() {
            StadisticsModel model = new StadisticsModel(0, 0, 0);
            StadisticsModel.MonthlyData md = new StadisticsModel.MonthlyData();
            md.setMonthLabel("Desembre 2025");
            model.setPreviousMonth(md);
            assertNotNull(model.getPreviousMonth());
            assertEquals("Desembre 2025", model.getPreviousMonth().getMonthLabel());
        }

        @Test
        @DisplayName("Should set and get comparison data")
        void shouldSetAndGetComparisonData() {
            StadisticsModel model = new StadisticsModel(0, 0, 0);
            StadisticsModel.ComparisonData cd = new StadisticsModel.ComparisonData(5, 10.5);
            model.setAskComparison(cd);
            model.setRedactComparison(cd);
            model.setFeedbackComparison(cd);
            assertEquals(5, model.getAskComparison().getAbsoluteDifference());
            assertEquals(5, model.getRedactComparison().getAbsoluteDifference());
            assertEquals(5, model.getFeedbackComparison().getAbsoluteDifference());
        }

        @Test
        @DisplayName("Should set and get yearlyData")
        void shouldSetAndGetYearlyData() {
            StadisticsModel model = new StadisticsModel(0, 0, 0);
            ArrayList<StadisticsModel.MonthlyData> yearly = new ArrayList<>();
            yearly.add(new StadisticsModel.MonthlyData("Gener", 1, 2, 3, new ArrayList<>(), new ArrayList<>()));
            model.setYearlyData(yearly);
            assertEquals(1, model.getYearlyData().size());
        }

        @Test
        @DisplayName("Should set and get prediction")
        void shouldSetAndGetPrediction() {
            StadisticsModel model = new StadisticsModel(0, 0, 0);
            model.setPrediction("Upward trend expected");
            assertEquals("Upward trend expected", model.getPrediction());
        }
    }

    @Nested
    @DisplayName("MonthlyData Tests")
    class MonthlyDataTests {

        @Test
        @DisplayName("Default constructor should create MonthlyData with null file lists")
        void defaultConstructorShouldCreateWithNullFileLists() {
            StadisticsModel.MonthlyData md = new StadisticsModel.MonthlyData();
            assertNull(md.getComplaintFiles());
            assertNull(md.getFeedbackFiles());
        }

        @Test
        @DisplayName("Parameterized constructor should set all fields")
        void parameterizedConstructorShouldSetFields() {
            ArrayList<ComplaintFile> cfs = new ArrayList<>();
            cfs.add(new ComplaintFile("c.pdf", null));
            ArrayList<FeedbackFile> ffs = new ArrayList<>();
            ffs.add(new FeedbackFile("f.json", null));

            StadisticsModel.MonthlyData md = new StadisticsModel.MonthlyData("Gener 2026", 10, 5, 3, cfs, ffs);
            assertEquals("Gener 2026", md.getMonthLabel());
            assertEquals(10, md.getAskInteractions());
            assertEquals(5, md.getRedactInteractions());
            assertEquals(3, md.getFeedbackCount());
            assertEquals(1, md.getComplaintFiles().size());
            assertEquals(1, md.getFeedbackFiles().size());
        }

        @Test
        @DisplayName("Setters should update MonthlyData fields")
        void settersShouldUpdate() {
            StadisticsModel.MonthlyData md = new StadisticsModel.MonthlyData();
            md.setMonthLabel("Febrer 2026");
            md.setAskInteractions(20);
            md.setRedactInteractions(10);
            md.setFeedbackCount(5);
            assertEquals("Febrer 2026", md.getMonthLabel());
            assertEquals(20, md.getAskInteractions());
            assertEquals(10, md.getRedactInteractions());
            assertEquals(5, md.getFeedbackCount());
        }

        @Test
        @DisplayName("fromYearMonth should create MonthlyData with Catalan label")
        void fromYearMonthShouldCreateCatalanLabel() {
            StadisticsModel.MonthlyData md = StadisticsModel.MonthlyData.fromYearMonth(YearMonth.of(2026, 1));
            assertNotNull(md);
            assertTrue(md.getMonthLabel().toLowerCase().contains("gener") || md.getMonthLabel().contains("January"));
            assertEquals(0, md.getAskInteractions());
            assertEquals(0, md.getRedactInteractions());
            assertEquals(0, md.getFeedbackCount());
            assertNotNull(md.getComplaintFiles());
            assertNotNull(md.getFeedbackFiles());
        }

        @Test
        @DisplayName("fromYearMonth should create correct label for different months")
        void fromYearMonthShouldCreateLabelsForDifferentMonths() {
            StadisticsModel.MonthlyData md = StadisticsModel.MonthlyData.fromYearMonth(YearMonth.of(2026, 6));
            assertNotNull(md);
            assertTrue(md.getMonthLabel().contains("2026"));
        }

        @Test
        @DisplayName("setComplaintFiles and setFeedbackFiles should work")
        void shouldSetFileLists() {
            StadisticsModel.MonthlyData md = new StadisticsModel.MonthlyData();
            md.setComplaintFiles(new ArrayList<>());
            md.setFeedbackFiles(new ArrayList<>());
            assertNotNull(md.getComplaintFiles());
            assertNotNull(md.getFeedbackFiles());
            assertTrue(md.getComplaintFiles().isEmpty());
            assertTrue(md.getFeedbackFiles().isEmpty());
        }
    }

    @Nested
    @DisplayName("ComparisonData Tests")
    class ComparisonDataTests {

        @Test
        @DisplayName("Default constructor should create with defaults")
        void defaultConstructorShouldCreateDefaults() {
            StadisticsModel.ComparisonData cd = new StadisticsModel.ComparisonData();
            assertEquals(0, cd.getAbsoluteDifference());
            assertEquals(0.0, cd.getPercentageChange());
        }

        @Test
        @DisplayName("Parameterized constructor should set fields")
        void parameterizedConstructorShouldSetFields() {
            StadisticsModel.ComparisonData cd = new StadisticsModel.ComparisonData(10, 25.5);
            assertEquals(10, cd.getAbsoluteDifference());
            assertEquals(25.5, cd.getPercentageChange());
        }

        @Test
        @DisplayName("Setters should update fields")
        void settersShouldUpdate() {
            StadisticsModel.ComparisonData cd = new StadisticsModel.ComparisonData();
            cd.setAbsoluteDifference(-5);
            cd.setPercentageChange(-12.3);
            assertEquals(-5, cd.getAbsoluteDifference());
            assertEquals(-12.3, cd.getPercentageChange());
        }
    }

    @Nested
    @DisplayName("toString() Tests")
    class ToStringTests {

        @Test
        @DisplayName("Should contain totals in HTML format")
        void shouldContainTotals() {
            StadisticsModel model = new StadisticsModel(10, 5, 3);
            String result = model.toString();
            assertTrue(result.contains("Stadistics Report"));
            assertTrue(result.contains("Total Ask logs:</strong> 10"));
            assertTrue(result.contains("Total Redact logs:</strong> 5"));
            assertTrue(result.contains("Total Feedback logs:</strong> 3"));
        }

        @Test
        @DisplayName("Should contain complaint file list when present")
        void shouldContainComplaintFileList() {
            ArrayList<ComplaintFile> complaints = new ArrayList<>();
            complaints.add(new ComplaintFile("complaint-001.pdf", null));
            ArrayList<FeedbackFile> feedbacks = new ArrayList<>();
            StadisticsModel model = new StadisticsModel(10, 5, 3, complaints, feedbacks);
            String result = model.toString();
            assertTrue(result.contains("Complaint Files:</strong> 1"));
            assertTrue(result.contains("complaint-001.pdf"));
        }

        @Test
        @DisplayName("Should contain feedback file list when present")
        void shouldContainFeedbackFileList() {
            ArrayList<FeedbackFile> feedbacks = new ArrayList<>();
            feedbacks.add(new FeedbackFile("fb-001.json", null));
            StadisticsModel model = new StadisticsModel(10, 5, 3, new ArrayList<>(), feedbacks);
            String result = model.toString();
            assertTrue(result.contains("Feedback files:</strong> 1"));
            assertTrue(result.contains("fb-001.json"));
        }

        @Test
        @DisplayName("Should contain file URLs when present")
        void shouldContainFileUrls() throws MalformedURLException {
            ArrayList<ComplaintFile> complaints = new ArrayList<>();
            complaints.add(new ComplaintFile("complaint-001.pdf",
                    new URL("https://example.com/complaint-001.pdf")));
            StadisticsModel model = new StadisticsModel(1, 1, 1, complaints, new ArrayList<>());
            String result = model.toString();
            assertTrue(result.contains("href=\"https://example.com/complaint-001.pdf\""));
        }

        @Test
        @DisplayName("Should contain monthly data table when yearlyData is present")
        void shouldContainMonthlyTable() {
            StadisticsModel model = new StadisticsModel(10, 5, 3);
            ArrayList<StadisticsModel.MonthlyData> yearly = new ArrayList<>();
            yearly.add(new StadisticsModel.MonthlyData("Gener 2026", 5, 2, 1, null, null));
            yearly.add(new StadisticsModel.MonthlyData("Febrer 2026", 5, 3, 2, null, null));
            model.setYearlyData(yearly);
            String result = model.toString();
            assertTrue(result.contains("Year-to-Date Monthly Data"));
            assertTrue(result.contains("<table"));
            assertTrue(result.contains("Gener 2026"));
            assertTrue(result.contains("Febrer 2026"));
            assertTrue(result.contains("</table>"));
        }

        @Test
        @DisplayName("Should contain current month section when set")
        void shouldContainCurrentMonthSection() {
            StadisticsModel model = new StadisticsModel(10, 5, 3);
            StadisticsModel.MonthlyData current = new StadisticsModel.MonthlyData("Març 2026", 10, 5, 3, null, null);
            model.setCurrentMonth(current);
            ArrayList<StadisticsModel.MonthlyData> yearly = new ArrayList<>();
            yearly.add(current);
            model.setYearlyData(yearly);
            String result = model.toString();
            assertTrue(result.contains("Current Month (Març 2026)"));
            assertTrue(result.contains("Ask interactions: 10"));
            assertTrue(result.contains("Redact interactions: 5"));
            assertTrue(result.contains("Feedback count: 3"));
        }

        @Test
        @DisplayName("Should contain previous month section when set")
        void shouldContainPreviousMonthSection() {
            StadisticsModel model = new StadisticsModel(10, 5, 3);
            StadisticsModel.MonthlyData prev = new StadisticsModel.MonthlyData("Febrer 2026", 8, 4, 2, null, null);
            model.setPreviousMonth(prev);
            ArrayList<StadisticsModel.MonthlyData> yearly = new ArrayList<>();
            yearly.add(prev);
            model.setYearlyData(yearly);
            String result = model.toString();
            assertTrue(result.contains("Previous Month (Febrer 2026)"));
        }

        @Test
        @DisplayName("Should contain comparison data when set")
        void shouldContainComparisonData() {
            StadisticsModel model = new StadisticsModel(10, 5, 3);
            StadisticsModel.MonthlyData current = new StadisticsModel.MonthlyData("Març 2026", 10, 5, 3, null, null);
            StadisticsModel.MonthlyData prev = new StadisticsModel.MonthlyData("Febrer 2026", 8, 4, 2, null, null);
            model.setCurrentMonth(current);
            model.setPreviousMonth(prev);
            model.setAskComparison(new StadisticsModel.ComparisonData(2, 25.0));
            model.setRedactComparison(new StadisticsModel.ComparisonData(1, 25.0));
            model.setFeedbackComparison(new StadisticsModel.ComparisonData(1, 50.0));
            ArrayList<StadisticsModel.MonthlyData> yearly = new ArrayList<>();
            yearly.add(prev);
            yearly.add(current);
            model.setYearlyData(yearly);
            String result = model.toString();
            assertTrue(result.contains("Comparison"));
            assertTrue(result.contains("Ask: +2"));
            assertTrue(result.contains("Redact: +1"));
            assertTrue(result.contains("Feedback: +1"));
        }

        @Test
        @DisplayName("Should handle null complaintFile list")
        void shouldHandleNullComplaintFile() {
            StadisticsModel model = new StadisticsModel(10, 5, 3);
            model.setComplaintFile(null);
            String result = model.toString();
            assertTrue(result.contains("Complaint Files:</strong> 0"));
        }

        @Test
        @DisplayName("Should handle null feedbackFile list")
        void shouldHandleNullFeedbackFile() {
            StadisticsModel model = new StadisticsModel(10, 5, 3);
            model.setFeedbackFile(null);
            String result = model.toString();
            assertTrue(result.contains("Feedback files:</strong> 0"));
        }

        @Test
        @DisplayName("Should handle null yearlyData")
        void shouldHandleNullYearlyData() {
            StadisticsModel model = new StadisticsModel(10, 5, 3);
            model.setYearlyData(null);
            String result = model.toString();
            assertTrue(result.contains("Stadistics Report"));
        }

        @Test
        @DisplayName("Should handle empty yearlyData")
        void shouldHandleEmptyYearlyData() {
            StadisticsModel model = new StadisticsModel(10, 5, 3);
            model.setYearlyData(new ArrayList<>());
            String result = model.toString();
            assertTrue(result.contains("Stadistics Report"));
            assertFalse(result.contains("Year-to-Date"));
        }

        @Test
        @DisplayName("Should handle null currentMonth and previousMonth in comparison")
        void shouldHandleNullCurrentAndPreviousMonth() {
            StadisticsModel model = new StadisticsModel(10, 5, 3);
            model.setAskComparison(new StadisticsModel.ComparisonData(2, 25.0));
            ArrayList<StadisticsModel.MonthlyData> yearly = new ArrayList<>();
            yearly.add(new StadisticsModel.MonthlyData("Gener 2026", 5, 3, 1, null, null));
            model.setYearlyData(yearly);
            String result = model.toString();
            assertTrue(result.contains("Comparison (Current vs Previous)"));
        }
    }

    @Nested
    @DisplayName("renderHtml() Tests")
    class RenderHtmlTests {

        @Test
        @DisplayName("renderHtml should return non-null string")
        void renderHtmlShouldReturnNonNull() {
            StadisticsModel model = new StadisticsModel(1, 1, 1);
            String result = model.renderHtml(
                    new cat.complai.services.stadistics.StadisticsHtmlRenderer(),
                    java.time.Instant.now(),
                    "Prediction text");
            assertNotNull(result);
            assertFalse(result.isBlank());
        }
    }
}
