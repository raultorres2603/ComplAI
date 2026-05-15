package cat.complai.utilities.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ApiKeyGenerator Unit Tests")
class ApiKeyGeneratorTest {

    @Test
    @DisplayName("Class loads successfully")
    void classLoads() {
        assertDoesNotThrow(() -> Class.forName("cat.complai.utilities.auth.ApiKeyGenerator"));
    }

    @Test
    @DisplayName("Generated key is 43 characters (32 bytes, base64url without padding)")
    void generatedKeyHasCorrectLength() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        assertEquals(43, key.length());
    }

    @Test
    @DisplayName("Generated key is URL-safe base64")
    void generatedKeyIsUrlSafe() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        assertTrue(key.matches("[A-Za-z0-9\\-_]+"));
    }
}
