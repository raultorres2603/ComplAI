package cat.complai.controllers.openrouter.dto;

import cat.complai.dto.openrouter.ComplainantIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the blank-to-null identity normalization contract.
 *
 * <p>The normalization is now performed by the controller boundary
 * ({@code OpenRouterController.safeIdentity()}) rather than the DTO, but the
 * behavioral contract on {@link ComplainantIdentity} remains unchanged.
 * These tests validate how identity data behaves when constructed with
 * null or blank fields, mirroring the controller's normalization.
 */
class RedactRequestTest {

    @Test
    void allFieldsNull_identityIsAbsent() {
        ComplainantIdentity id = new ComplainantIdentity(null, null, null);
        assertNull(id.name());
        assertNull(id.surname());
        assertNull(id.idNumber());
        assertFalse(id.isComplete());
        assertFalse(id.isPartiallyProvided());
    }

    @Test
    void allFieldsBlank_isCompleteReturnsFalse() {
        ComplainantIdentity id = new ComplainantIdentity(" ", "  ", "\t");
        assertFalse(id.isComplete(),
                "Whitespace-only fields must not be considered complete");
        assertFalse(id.isPartiallyProvided(),
                "Whitespace-only values should not count as partially provided");
    }

    @Test
    void mixOfNullAndBlank_isCompleteReturnsFalse() {
        ComplainantIdentity id = new ComplainantIdentity(null, "  ", null);
        assertFalse(id.isComplete());
        assertNull(id.name());
        assertNotNull(id.surname());
    }

    @Test
    void allFieldsPresent_isCompleteReturnsTrue() {
        ComplainantIdentity id = new ComplainantIdentity("Joan", "Torres", "12345678A");
        assertTrue(id.isComplete());
        assertTrue(id.isPartiallyProvided());
        assertEquals("Joan", id.name());
        assertEquals("Torres", id.surname());
        assertEquals("12345678A", id.idNumber());
    }

    @Test
    void partialIdentity_isNotComplete() {
        ComplainantIdentity id = new ComplainantIdentity("Joan", "  ", null);
        assertFalse(id.isComplete(), "Partial identity must not be considered complete");
        assertTrue(id.isPartiallyProvided(), "At least one real field qualifies as partially provided");
    }
}

