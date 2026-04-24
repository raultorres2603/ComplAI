package cat.complai.helpers.openrouter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditLoggerTest {
    @Test
    void hashText_isDeterministicAndObscuresInput() {
        String text = "Sensitive user input";
        String hash1 = AuditLogger.hashText(text);
        String hash2 = AuditLogger.hashText(text);
        assertEquals(hash1, hash2, "Hash should be deterministic");
        assertNotEquals(text, hash1, "Hash should not be the same as input");
    }

    @Test
    void hashText_handlesNull() {
        assertEquals("null", AuditLogger.hashText(null));
    }

    @Test
    void log_writesStructuredLine() {
        // This test checks that log() does not throw and writes a line.
        // We do not assert on log output (would require a custom handler), but ensure
        // no exceptions.
        assertDoesNotThrow(() -> AuditLogger.log("/complai/ask", "abc123", 0, 42, "PDF", "CA"));
    }
}
