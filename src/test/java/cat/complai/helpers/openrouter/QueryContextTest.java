package cat.complai.helpers.openrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("QueryContext Tests")
class QueryContextTest {

    @Nested
    @DisplayName("Constructor Validation Tests")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Should throw IllegalArgumentException when detectedLanguage is null")
        void constructorShouldThrowWhenLanguageIsNull() {
            assertThrows(IllegalArgumentException.class,
                    () -> new QueryContext("query", null, List.of("token")),
                    "detectedLanguage cannot be null");
        }

        @Test
        @DisplayName("Should succeed with valid parameters")
        void constructorShouldSucceedWithValidParams() {
            QueryContext ctx = new QueryContext("Hola", "CA", List.of("hola"));
            assertNotNull(ctx);
        }
    }

    @Nested
    @DisplayName("Record Accessor Tests")
    class AccessorTests {

        @Test
        @DisplayName("Should return original query via accessor")
        void shouldReturnOriginalQuery() {
            QueryContext ctx = new QueryContext("Bon dia", "CA", List.of("bon", "dia"));
            assertEquals("Bon dia", ctx.originalQuery());
        }

        @Test
        @DisplayName("Should return detected language via accessor")
        void shouldReturnDetectedLanguage() {
            QueryContext ctx = new QueryContext("Hello", "EN", List.of("hello"));
            assertEquals("EN", ctx.detectedLanguage());
        }

        @Test
        @DisplayName("Should return tokens via accessor")
        void shouldReturnTokens() {
            List<String> tokens = List.of("com", "funciona", "aixo");
            QueryContext ctx = new QueryContext("Com funciona aixo", "CA", tokens);
            assertEquals(tokens, ctx.tokens());
        }
    }

    @Nested
    @DisplayName("Record Utility Method Tests")
    class UtilityMethodTests {

        @Test
        @DisplayName("toString() should be available and contain fields")
        void toStringShouldContainFields() {
            QueryContext ctx = new QueryContext("test", "ES", List.of("test"));
            String str = ctx.toString();
            assertNotNull(str);
            assertTrue(str.contains("test"));
            assertTrue(str.contains("ES"));
        }
    }
}
