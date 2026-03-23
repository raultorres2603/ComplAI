package cat.complai.openrouter.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("QuestionCategoryDetector Tests")
class QuestionCategoryDetectorTest {

    @Test
    @DisplayName("should detect PARKING category")
    void testDetectParking() {
        assertEquals(QuestionCategory.PARKING,
                QuestionCategoryDetector.detectCategory("How do I get a parking permit?"));
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("parking fine"));
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("parking zone"));
    }

    @Test
    @DisplayName("should detect TAX category")
    void testDetectTax() {
        assertEquals(QuestionCategory.TAX, QuestionCategoryDetector.detectCategory("What are the tax rates?"));
        assertEquals(QuestionCategory.TAX, QuestionCategoryDetector.detectCategory("municipal tax"));
        assertEquals(QuestionCategory.TAX, QuestionCategoryDetector.detectCategory("tax deduction"));
    }

    @Test
    @DisplayName("should detect GARBAGE category")
    void testDetectGarbage() {
        assertEquals(QuestionCategory.GARBAGE, QuestionCategoryDetector.detectCategory("When is garbage collection?"));
        assertEquals(QuestionCategory.GARBAGE, QuestionCategoryDetector.detectCategory("recycling program"));
        assertEquals(QuestionCategory.GARBAGE, QuestionCategoryDetector.detectCategory("waste management"));
    }

    @Test
    @DisplayName("should detect LIBRARY category")
    void testDetectLibrary() {
        assertEquals(QuestionCategory.LIBRARY, QuestionCategoryDetector.detectCategory("What are library hours?"));
        assertEquals(QuestionCategory.LIBRARY, QuestionCategoryDetector.detectCategory("book reservation"));
        assertEquals(QuestionCategory.LIBRARY, QuestionCategoryDetector.detectCategory("library membership"));
    }

    @Test
    @DisplayName("should detect COMPLAINT category")
    void testDetectComplaint() {
        assertEquals(QuestionCategory.COMPLAINT, QuestionCategoryDetector.detectCategory("How do I file a complaint?"));
        assertEquals(QuestionCategory.COMPLAINT, QuestionCategoryDetector.detectCategory("grievance process"));
        assertEquals(QuestionCategory.COMPLAINT, QuestionCategoryDetector.detectCategory("legal claim"));
    }

    @Test
    @DisplayName("should detect ADMINISTRATION category")
    void testDetectAdministration() {
        assertEquals(QuestionCategory.ADMINISTRATION,
                QuestionCategoryDetector.detectCategory("How do I get a business license?"));
        assertEquals(QuestionCategory.ADMINISTRATION,
                QuestionCategoryDetector.detectCategory("certificate registration"));
        assertEquals(QuestionCategory.ADMINISTRATION, QuestionCategoryDetector.detectCategory("permit application"));
    }

    @Test
    @DisplayName("should default to OTHER category")
    void testDefaultToOther() {
        assertEquals(QuestionCategory.OTHER, QuestionCategoryDetector.detectCategory("What is the weather like?"));
        assertEquals(QuestionCategory.OTHER, QuestionCategoryDetector.detectCategory("random question"));
        assertEquals(QuestionCategory.OTHER, QuestionCategoryDetector.detectCategory(""));
    }

    @Test
    @DisplayName("should handle null input gracefully")
    void testHandleNullInput() {
        assertEquals(QuestionCategory.OTHER, QuestionCategoryDetector.detectCategory(null));
    }

    @Test
    @DisplayName("should handle blank input gracefully")
    void testHandleBlankInput() {
        assertEquals(QuestionCategory.OTHER, QuestionCategoryDetector.detectCategory("   "));
    }

    @Test
    @DisplayName("should be case-insensitive")
    void testCaseInsensitive() {
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("PARKING PERMIT"));
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("Parking Permit"));
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("PaRkInG permit"));
    }

    @Test
    @DisplayName("should detect Catalan keywords in PARKING")
    void testDetectCatalanParkingKeywords() {
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("aparcament permís"));
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("plaça estacionament"));
    }

    @Test
    @DisplayName("should detect Spanish keywords in TAX")
    void testDetectSpanishTaxKeywords() {
        assertEquals(QuestionCategory.TAX, QuestionCategoryDetector.detectCategory("impuesto municipal"));
        assertEquals(QuestionCategory.TAX, QuestionCategoryDetector.detectCategory("tributo"));
    }

    @Test
    @DisplayName("should detect Catalan keywords in COMPLAINT")
    void testDetectCatalanComplaintKeywords() {
        assertEquals(QuestionCategory.COMPLAINT, QuestionCategoryDetector.detectCategory("reclamació"));
        assertEquals(QuestionCategory.COMPLAINT, QuestionCategoryDetector.detectCategory("queixa demanda"));
    }

    @Test
    @DisplayName("should prioritize first matching category")
    void testPrioritizesFirstMatch() {
        // Parking appears before tax in keywords, so should match first
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("parking and taxes"));
    }

    @Test
    @DisplayName("should correctly categorize various questions")
    void testCategorizeVariousQuestions() {
        String[][] testCases = {
                { "How to park in the city", "PARKING" },
                { "Vehicle permit needed", "PARKING" },
                { "Municipal tax rate", "TAX" },
                { "Filing taxes", "TAX" },
                { "Garbage collection schedule", "GARBAGE" },
                { "Recycling bins", "GARBAGE" },
                { "Library opening hours", "LIBRARY" },
                { "Book lending policy", "LIBRARY" },
                { "Complaint about service", "COMPLAINT" },
                { "Grievance procedure", "COMPLAINT" },
                { "Business registration", "ADMINISTRATION" },
                { "License application", "ADMINISTRATION" },
                { "What is the answer to life", "OTHER" }
        };

        for (String[] testCase : testCases) {
            String question = testCase[0];
            String expectedCategoryStr = testCase[1];
            QuestionCategory expected = QuestionCategory.valueOf(expectedCategoryStr);
            assertEquals(expected, QuestionCategoryDetector.detectCategory(question),
                    "Failed to categorize: " + question);
        }
    }

    @Test
    @DisplayName("should handle punctuation in questions")
    void testHandlePunctuation() {
        // Punctuation should not prevent detection
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("Parking? How?"));
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("PARKING!!!"));
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("Parking..."));
    }

    @Test
    @DisplayName("should handle leading/trailing whitespace")
    void testHandleWhitespace() {
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("  parking permit  "));
        assertEquals(QuestionCategory.PARKING, QuestionCategoryDetector.detectCategory("\nparking\t"));
    }
}
