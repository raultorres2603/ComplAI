package cat.complai.openrouter.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CommonResponseEntry Tests")
class CommonResponseEntryTest {

    @Test
    @DisplayName("should create valid entry with all fields")
    void testCreateValidEntry() {
        CommonResponseEntry entry = new CommonResponseEntry(
                QuestionCategory.PARKING,
                "elprat",
                "How to get a parking permit?",
                "To get a parking permit, visit the city office...");

        assertEquals(QuestionCategory.PARKING, entry.category());
        assertEquals("elprat", entry.city());
        assertEquals("How to get a parking permit?", entry.questionTemplate());
        assertEquals("To get a parking permit, visit the city office...", entry.response());
    }

    @Test
    @DisplayName("should create entry with null city (global)")
    void testCreateEntryWithNullCity() {
        CommonResponseEntry entry = new CommonResponseEntry(
                QuestionCategory.TAX,
                null,
                "What is the tax rate?",
                "The tax rate varies by location...");

        assertNull(entry.city());
        assertEquals(QuestionCategory.TAX, entry.category());
    }

    @Test
    @DisplayName("should reject null category")
    void testRejectNullCategory() {
        assertThrows(IllegalArgumentException.class, () -> {
            new CommonResponseEntry(null, "elprat", "Question?", "Answer");
        });
    }

    @Test
    @DisplayName("should reject null response")
    void testRejectNullResponse() {
        assertThrows(IllegalArgumentException.class, () -> {
            new CommonResponseEntry(QuestionCategory.PARKING, "elprat", "Question?", null);
        });
    }

    @Test
    @DisplayName("should reject blank response")
    void testRejectBlankResponse() {
        assertThrows(IllegalArgumentException.class, () -> {
            new CommonResponseEntry(QuestionCategory.PARKING, "elprat", "Question?", "   ");
        });
    }

    @Test
    @DisplayName("should accept null question template")
    void testAcceptNullQuestionTemplate() {
        assertDoesNotThrow(() -> {
            new CommonResponseEntry(QuestionCategory.LIBRARY, "elprat", null, "The library is open Monday-Friday");
        });
    }
}
