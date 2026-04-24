package cat.complai.controllers.openrouter.dto;

import cat.complai.dto.openrouter.ComplainantIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RedactRequest#getComplainantIdentity()}.
 *
 * The method is the single point responsible for normalising raw HTTP input into
 * the domain's {@link ComplainantIdentity} record. These tests verify that blank
 * and whitespace-only values are treated as absent, and that all returned values
 * are trimmed before crossing the service boundary.
 */
class RedactRequestTest {

    // -------------------------------------------------------------------------
    // Returns null — all fields absent
    // -------------------------------------------------------------------------

    @Test
    void getComplainantIdentity_allNull_returnsNull() {
        RedactRequest request = new RedactRequest("complaint", null, null, null, null, null);
        assertNull(request.getComplainantIdentity());
    }

    @Test
    void getComplainantIdentity_allBlank_returnsNull() {
        RedactRequest request = new RedactRequest("complaint", null, null, " ", "  ", "\t");
        assertNull(request.getComplainantIdentity(),
                "Whitespace-only fields must be treated as absent, not as provided values");
    }

    @Test
    void getComplainantIdentity_mixOfNullAndBlank_returnsNull() {
        RedactRequest request = new RedactRequest("complaint", null, null, null, "  ", null);
        assertNull(request.getComplainantIdentity(),
                "A single blank field must not cause a partial identity to be returned");
    }

    // -------------------------------------------------------------------------
    // Returns identity — at least one real field present
    // -------------------------------------------------------------------------

    @Test
    void getComplainantIdentity_allFieldsPresent_returnsCompleteIdentity() {
        RedactRequest request = new RedactRequest("complaint", null, null, "Joan", "Torres", "12345678A");
        ComplainantIdentity identity = request.getComplainantIdentity();

        assertNotNull(identity);
        assertEquals("Joan", identity.name());
        assertEquals("Torres", identity.surname());
        assertEquals("12345678A", identity.idNumber());
        assertTrue(identity.isComplete());
    }

    @Test
    void getComplainantIdentity_fieldsWithSurroundingWhitespace_areTrimmed() {
        RedactRequest request = new RedactRequest("complaint", null, null, "  Joan  ", " Torres ", "  12345678A  ");
        ComplainantIdentity identity = request.getComplainantIdentity();

        assertNotNull(identity);
        assertEquals("Joan", identity.name(), "name must be trimmed");
        assertEquals("Torres", identity.surname(), "surname must be trimmed");
        assertEquals("12345678A", identity.idNumber(), "idNumber must be trimmed");
    }

    @Test
    void getComplainantIdentity_partialIdentity_blankFieldsBecomNull() {
        // Only name is real; surname is blank, id is null.
        RedactRequest request = new RedactRequest("complaint", null, null, "Joan", "  ", null);
        ComplainantIdentity identity = request.getComplainantIdentity();

        assertNotNull(identity, "At least one real field means identity is not null");
        assertEquals("Joan", identity.name());
        assertNull(identity.surname(), "Blank surname must be normalised to null");
        assertNull(identity.idNumber());
        assertFalse(identity.isComplete(), "Partial identity must not be considered complete");
        assertTrue(identity.isPartiallyProvided());
    }
}

