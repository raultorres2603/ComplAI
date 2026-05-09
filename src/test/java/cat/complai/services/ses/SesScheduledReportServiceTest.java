package cat.complai.services.ses;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cat.complai.config.ISesRecipientProvider;
import cat.complai.exceptions.ses.CloudWatchLogsException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SesScheduledReportService}.
 *
 * <p>Tests the business logic in isolation without any Micronaut context — no
 * HTTP server binding.  Uses Mockito to mock the injected dependencies.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SesScheduledReportService Unit Tests")
class SesScheduledReportServiceTest {

    private static final String RECIPIENT = "admin@complai.test";

    @Mock
    private IEmailService emailService;

    @Mock
    private ISesRecipientProvider recipientProvider;

    private SesScheduledReportService service;

    @BeforeEach
    void setUp() {
        service = new SesScheduledReportService(emailService, recipientProvider);
    }

    @Nested
    @DisplayName("run — success path")
    class RunSuccess {

        @Test
        @DisplayName("returns OK and calls emailService with correct subject")
        void run_validRecipient_returnsOk() {
            // Arrange
            org.mockito.Mockito.when(recipientProvider.getRecipientEmail()).thenReturn(RECIPIENT);

            // Act
            String result = service.run();

            // Assert
            assertTrue(result.startsWith("OK:"), "result should start with OK: " + result);
            assertTrue(result.contains("***"), "result should contain masked recipient");
            verify(emailService).sendStadistics(anyString(), anyString());
        }

        @Test
        @DisplayName("sends report to the configured recipient email")
        void run_passesRecipientFromProvider() {
            org.mockito.Mockito.when(recipientProvider.getRecipientEmail()).thenReturn(RECIPIENT);

            service.run();

            verify(emailService).sendStadistics(org.mockito.ArgumentMatchers.eq(RECIPIENT), anyString());
        }
    }

    @Nested
    @DisplayName("run — recipient validation")
    class RunRecipientValidation {

        @Test
        @DisplayName("returns ERROR when recipient is blank")
        void run_blankRecipient_returnsError() {
            org.mockito.Mockito.when(recipientProvider.getRecipientEmail()).thenReturn("   ");

            String result = service.run();

            assertTrue(result.startsWith("ERROR"), "result should start with ERROR: " + result);
            verify(emailService, never()).sendStadistics(anyString(), anyString());
        }

        @Test
        @DisplayName("returns ERROR when recipient is null")
        void run_nullRecipient_returnsError() {
            org.mockito.Mockito.when(recipientProvider.getRecipientEmail()).thenReturn(null);

            String result = service.run();

            assertTrue(result.startsWith("ERROR"), "result should start with ERROR: " + result);
            verify(emailService, never()).sendStadistics(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("run — service errors")
    class RunServiceErrors {

        @Test
        @DisplayName("rethrows CloudWatchLogsException for retry")
        void run_cloudWatchLogsException_rethrows() {
            org.mockito.Mockito.when(recipientProvider.getRecipientEmail()).thenReturn(RECIPIENT);
            doThrow(new CloudWatchLogsException("CloudWatch Logs unavailable"))
                    .when(emailService).sendStadistics(anyString(), anyString());

            CloudWatchLogsException thrown = assertThrows(
                    CloudWatchLogsException.class,
                    service::run);

            assertTrue(thrown.getMessage().contains("CloudWatch"));
        }

        @Test
        @DisplayName("wraps generic exceptions in RuntimeException")
        void run_genericException_wrapsInRuntimeException() {
            org.mockito.Mockito.when(recipientProvider.getRecipientEmail()).thenReturn(RECIPIENT);
            RuntimeException cause = new RuntimeException("SES send failed");
            doThrow(cause).when(emailService).sendStadistics(anyString(), anyString());

            RuntimeException thrown = assertThrows(
                    RuntimeException.class,
                    service::run);

            assertTrue(thrown.getMessage().contains("Statistics report failed"));
            assertSame(cause, thrown.getCause());
        }
    }
}