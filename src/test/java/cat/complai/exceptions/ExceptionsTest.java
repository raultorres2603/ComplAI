package cat.complai.exceptions;

import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.exceptions.ses.CloudWatchLogsException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Exception Classes Tests")
class ExceptionsTest {

    @Nested
    @DisplayName("IdentityTokenValidationException Tests")
    class IdentityTokenValidationExceptionTests {

        @Test
        @DisplayName("Constructor with message sets message")
        void constructorWithMessage() {
            IdentityTokenValidationException ex = new IdentityTokenValidationException("Token expired");
            assertEquals("Token expired", ex.getMessage());
        }

        @Test
        @DisplayName("Constructor with message and cause sets both")
        void constructorWithMessageAndCause() {
            Throwable cause = new RuntimeException("underlying");
            IdentityTokenValidationException ex = new IdentityTokenValidationException("Invalid signature", cause);
            assertEquals("Invalid signature", ex.getMessage());
            assertSame(cause, ex.getCause());
        }
    }

    @Nested
    @DisplayName("OpenRouterStreamingException Tests")
    class OpenRouterStreamingExceptionTests {

        @Test
        @DisplayName("Three-arg constructor sets all fields")
        void threeArgConstructor() {
            OpenRouterStreamingException ex = new OpenRouterStreamingException(
                    OpenRouterErrorCode.UPSTREAM, "Upstream error", 502);
            assertEquals("Upstream error", ex.getMessage());
            assertEquals(OpenRouterErrorCode.UPSTREAM, ex.getErrorCode());
            assertEquals(502, ex.getUpstreamStatus());
        }

        @Test
        @DisplayName("Four-arg constructor sets all fields including cause")
        void fourArgConstructor() {
            Throwable cause = new RuntimeException("connection timeout");
            OpenRouterStreamingException ex = new OpenRouterStreamingException(
                    OpenRouterErrorCode.TIMEOUT, "Request timed out", null, cause);
            assertEquals("Request timed out", ex.getMessage());
            assertEquals(OpenRouterErrorCode.TIMEOUT, ex.getErrorCode());
            assertNull(ex.getUpstreamStatus());
            assertSame(cause, ex.getCause());
        }

        @Test
        @DisplayName("getErrorCode returns the error code")
        void getErrorCode() {
            OpenRouterStreamingException ex = new OpenRouterStreamingException(
                    OpenRouterErrorCode.VALIDATION, "Bad request", 400);
            assertEquals(OpenRouterErrorCode.VALIDATION, ex.getErrorCode());
        }

        @Test
        @DisplayName("getUpstreamStatus returns null when not set")
        void getUpstreamStatusNull() {
            OpenRouterStreamingException ex = new OpenRouterStreamingException(
                    OpenRouterErrorCode.INTERNAL, "Internal error", null);
            assertNull(ex.getUpstreamStatus());
        }
    }

    @Nested
    @DisplayName("CloudWatchLogsException Tests")
    class CloudWatchLogsExceptionTests {

        @Test
        @DisplayName("Constructor with message sets message")
        void constructorWithMessage() {
            CloudWatchLogsException ex = new CloudWatchLogsException("Query failed");
            assertEquals("Query failed", ex.getMessage());
        }

        @Test
        @DisplayName("Constructor with message and cause sets both")
        void constructorWithMessageAndCause() {
            Throwable cause = new RuntimeException("AWS error");
            CloudWatchLogsException ex = new CloudWatchLogsException("Logs unavailable", cause);
            assertEquals("Logs unavailable", ex.getMessage());
            assertSame(cause, ex.getCause());
        }
    }
}
