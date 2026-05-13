package cat.complai.services.openrouter.validation;

import cat.complai.dto.openrouter.OpenRouterErrorCode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InputValidationServiceTest {

    private static final int DEFAULT_MAX_LENGTH = 5000;

    private final InputValidationService service = new InputValidationService(DEFAULT_MAX_LENGTH);

    @Nested
    class ValidateQuestion {

        @Test
        void nullInput_returnsValidationError() {
            var result = service.validateQuestion(null);
            assertTrue(result.isPresent());
            assertFalse(result.get().isSuccess());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void blankInput_returnsValidationError() {
            var result = service.validateQuestion("   ");
            assertTrue(result.isPresent());
            assertFalse(result.get().isSuccess());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void emptyString_returnsValidationError() {
            var result = service.validateQuestion("");
            assertTrue(result.isPresent());
            assertFalse(result.get().isSuccess());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void inputExceedingMaxLength_returnsValidationError() {
            String longInput = "a".repeat(DEFAULT_MAX_LENGTH + 1);
            var result = service.validateQuestion(longInput);
            assertTrue(result.isPresent());
            assertFalse(result.get().isSuccess());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void validInput_returnsEmpty() {
            var result = service.validateQuestion("What are the office hours?");
            assertTrue(result.isEmpty());
        }

        @Test
        void inputAtMaxLength_returnsEmpty() {
            String input = "a".repeat(DEFAULT_MAX_LENGTH);
            var result = service.validateQuestion(input);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class ValidateRedactInput {

        @Test
        void nullInput_returnsValidationError() {
            var result = service.validateRedactInput(null);
            assertTrue(result.isPresent());
            assertFalse(result.get().isSuccess());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void blankInput_returnsValidationError() {
            var result = service.validateRedactInput("   ");
            assertTrue(result.isPresent());
            assertFalse(result.get().isSuccess());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void inputExceedingMaxLength_returnsValidationError() {
            String longInput = "a".repeat(DEFAULT_MAX_LENGTH + 1);
            var result = service.validateRedactInput(longInput);
            assertTrue(result.isPresent());
            assertFalse(result.get().isSuccess());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_english_wantToBeAnonymous() {
            var result = service.validateRedactInput("I want to be anonymous");
            assertTrue(result.isPresent());
            assertFalse(result.get().isSuccess());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_english_remainAnonymous() {
            var result = service.validateRedactInput("I wish to remain anonymous");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_english_stayAnonymous() {
            var result = service.validateRedactInput("I want to stay anonymous");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_english_anonymously() {
            var result = service.validateRedactInput("I am filing this anonymously");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_spanish_quieroSerAnonimo() {
            var result = service.validateRedactInput("quiero ser anónimo");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_spanish_deFormaAnonima() {
            var result = service.validateRedactInput("de forma anónima");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_catalan_vullSerAnonim() {
            var result = service.validateRedactInput("vull ser anònim");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_catalan_deFormaAnonima() {
            var result = service.validateRedactInput("de forma anònima");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_catalan_vullQueSiguiAnonim() {
            var result = service.validateRedactInput("vull que sigui anònim");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_spanish_quieroQueSeaAnonimo() {
            var result = service.validateRedactInput("quiero que sea anónimo");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_spanish_deManeraAnonima() {
            var result = service.validateRedactInput("de manera anónima");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_catalan_deManeraAnonima() {
            var result = service.validateRedactInput("de manera anònima");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void anonymityDetected_spanish_quieroHacerloAnonimo() {
            var result = service.validateRedactInput("quiero hacerlo anónimo");
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void normalComplaint_returnsEmpty() {
            var result = service.validateRedactInput("This is a normal complaint about noise from the airport.");
            assertTrue(result.isEmpty());
        }

        @Test
        void inputAtMaxLength_returnsEmpty() {
            String input = "a".repeat(DEFAULT_MAX_LENGTH);
            var result = service.validateRedactInput(input);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    class CustomMaxLength {

        @Test
        void customMaxLength_respected() {
            var custom = new InputValidationService(100);
            String question = "a".repeat(101);
            var result = custom.validateQuestion(question);
            assertTrue(result.isPresent());
            assertEquals(OpenRouterErrorCode.VALIDATION, result.get().getErrorCode());
        }

        @Test
        void customMaxLength_underLimit_valid() {
            var custom = new InputValidationService(100);
            String question = "a".repeat(99);
            var result = custom.validateQuestion(question);
            assertTrue(result.isEmpty());
        }
    }
}
