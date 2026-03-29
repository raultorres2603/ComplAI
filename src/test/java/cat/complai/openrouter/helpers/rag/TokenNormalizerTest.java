package cat.complai.openrouter.helpers.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenNormalizerTest {

    @Test
    void normalizeForSearch_foldsAccentsAndWhitespace() {
        String normalized = TokenNormalizer.normalizeForSearch("  tràmit    públic  ");
        assertEquals("tramit public", normalized);
    }

    @Test
    void normalizeForSearch_splitsPunctuationIntoSpaces() {
        String normalized = TokenNormalizer.normalizeForSearch("cafe-restaurant @ 12:30");
        assertEquals("cafe restaurant 12 30", normalized);
    }

    @Test
    void tokenize_handlesNullAndBlank() {
        assertTrue(TokenNormalizer.tokenize(null).isEmpty());
        assertTrue(TokenNormalizer.tokenize("  ").isEmpty());
    }

    @Test
    void tokenize_isDeterministic() {
        List<String> tokens = TokenNormalizer.tokenize("Àjuntament, tràmit! tràmit");
        assertEquals(List.of("ajuntament", "tramit", "tramit"), tokens);
    }
}
