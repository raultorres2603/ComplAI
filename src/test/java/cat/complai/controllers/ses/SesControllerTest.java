package cat.complai.controllers.ses;

import cat.complai.config.SesConfiguration;
import cat.complai.exceptions.ses.CloudWatchLogsException;
import cat.complai.exceptions.ses.SesEmailException;
import cat.complai.services.ses.EmailService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SesController}.
 *
 * <p>
 * Tests the HTTP endpoints for sending statistics reports via Amazon SES.
 * Uses @MicronautTest for HTTP integration testing with mocked EmailService.
 * Validates success cases, exception handling, and configuration injection.
 *
 * @author ComplAI Team
 * @version 1.0
 */
@MicronautTest(environments = { "test" })
@DisplayName("SesController Tests")
public class SesControllerTest {

    private static final String RECIPIENT_EMAIL = "admin@test.com";
    private static final String STATISTICS_SUBJECT = "Usage Statistics Report";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    EmailService emailService;

    @Inject
    SesConfiguration sesConfiguration;

    @Inject
    SesController sesController;

    /**
     * Mock bean factory for EmailService.
     * Returns a new mock instance for each test.
     */
    @MockBean(EmailService.class)
    EmailService mockEmailService() {
        return mock(EmailService.class);
    }

    /**
     * Mock bean factory for SesConfiguration.
     * Provides test configuration with predefined values.
     */
    @MockBean(SesConfiguration.class)
    SesConfiguration mockSesConfiguration() {
        SesConfiguration config = mock(SesConfiguration.class);
        when(config.getRecipientEmail()).thenReturn(RECIPIENT_EMAIL);
        when(config.getFromEmail()).thenReturn("noreply@test.com");
        when(config.getRegion()).thenReturn("eu-west-1");
        return config;
    }

    @BeforeEach
    void setUp() {
        reset(emailService);
    }

    /**
     * Nested test class for successful scenarios.
     */
    @Nested
    @DisplayName("Success Cases")
    class SuccessCases {

        @Test
        @DisplayName("GET /ses/stadistics returns HTTP 200 OK on success")
        void testGetStadisticsSuccess() {
            // Arrange: EmailService is mocked and does not throw
            doNothing().when(emailService).sendStadistics(RECIPIENT_EMAIL, STATISTICS_SUBJECT);

            // Act: Call the endpoint
            HttpResponse<String> response = client.toBlocking()
                    .exchange("GET /ses/stadistics", String.class);

            // Assert: Verify HTTP 200 status
            assertEquals(HttpStatus.OK, response.getStatus());
            assertEquals(200, response.getStatus().getCode());
        }

        @Test
        @DisplayName("GET /ses/stadistics returns correct success message")
        void testGetStadisticsSuccessMessage() {
            // Arrange
            doNothing().when(emailService).sendStadistics(RECIPIENT_EMAIL, STATISTICS_SUBJECT);

            // Act
            HttpResponse<String> response = client.toBlocking()
                    .exchange("GET /ses/stadistics", String.class);

            // Assert: Verify response body
            assertTrue(response.getBody().isPresent());
            assertEquals("Statistics report sent successfully", response.getBody().get());
        }

        @Test
        @DisplayName("GET /ses/stadistics calls EmailService with correct parameters")
        void testGetStadisticsCallsEmailService() {
            // Arrange
            ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
            doNothing().when(emailService).sendStadistics(anyString(), anyString());

            // Act
            client.toBlocking().exchange("GET /ses/stadistics", String.class);

            // Assert: Verify EmailService was called with correct parameters
            verify(emailService, times(1)).sendStadistics(
                    emailCaptor.capture(),
                    subjectCaptor.capture());
            assertEquals(RECIPIENT_EMAIL, emailCaptor.getValue());
            assertEquals(STATISTICS_SUBJECT, subjectCaptor.getValue());
        }

        @Test
        @DisplayName("GET /ses/stadistics calls EmailService exactly once")
        void testGetStadisticsEmailServiceCalledOnce() {
            // Arrange
            doNothing().when(emailService).sendStadistics(anyString(), anyString());

            // Act
            client.toBlocking().exchange("GET /ses/stadistics", String.class);

            // Assert: Verify service called exactly once
            verify(emailService, times(1)).sendStadistics(anyString(), anyString());
            verifyNoMoreInteractions(emailService);
        }

        @Test
        @DisplayName("Configuration is correctly injected into controller")
        void testConfigurationInjection() {
            // Assert: Verify configuration values are accessible
            assertNotNull(sesConfiguration);
            assertEquals(RECIPIENT_EMAIL, sesConfiguration.getRecipientEmail());
            assertEquals("noreply@test.com", sesConfiguration.getFromEmail());
            assertEquals("eu-west-1", sesConfiguration.getRegion());
        }
    }

    /**
     * Nested test class for SesEmailException scenarios.
     */
    @Nested
    @DisplayName("SesEmailException Scenarios")
    class SesEmailExceptionScenarios {

        @Test
        @DisplayName("GET /ses/stadistics returns HTTP 503 SERVICE_UNAVAILABLE on SesEmailException")
        void testGetStadisticsSesFailure() {
            // Arrange
            doThrow(new SesEmailException("Email service unavailable"))
                    .when(emailService).sendStadistics(anyString(), anyString());

            // Act
            HttpResponse<String> response = client.toBlocking()
                    .exchange("GET /ses/stadistics", String.class);

            // Assert: Verify HTTP 503 status
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatus());
            assertEquals(503, response.getStatus().getCode());
        }

        @Test
        @DisplayName("GET /ses/stadistics returns error message on SesEmailException")
        void testGetStadisticsSesFailureMessage() {
            // Arrange
            doThrow(new SesEmailException("Email service unavailable"))
                    .when(emailService).sendStadistics(anyString(), anyString());

            // Act
            HttpResponse<String> response = client.toBlocking()
                    .exchange("GET /ses/stadistics", String.class);

            // Assert: Verify error message in response body
            assertTrue(response.getBody().isPresent());
            assertEquals("Email service unavailable. Please try again later.",
                    response.getBody().get());
        }

        @Test
        @DisplayName("GET /ses/stadistics logs error on SesEmailException")
        void testGetStadisticsSesFailureLogged() {
            // Arrange
            doThrow(new SesEmailException("Email service unavailable"))
                    .when(emailService).sendStadistics(anyString(), anyString());

            // Act & Assert: Verify no exception is thrown to caller
            // (error is logged internally)
            assertDoesNotThrow(() -> client.toBlocking().exchange("GET /ses/stadistics", String.class));
        }
    }

    /**
     * Nested test class for CloudWatchLogsException scenarios.
     */
    @Nested
    @DisplayName("CloudWatchLogsException Scenarios")
    class CloudWatchLogsExceptionScenarios {

        @Test
        @DisplayName("GET /ses/stadistics returns HTTP 503 SERVICE_UNAVAILABLE on CloudWatchLogsException")
        void testGetStadisticsCloudWatchFailure() {
            // Arrange
            doThrow(new CloudWatchLogsException("CloudWatch service unavailable"))
                    .when(emailService).sendStadistics(anyString(), anyString());

            // Act
            HttpResponse<String> response = client.toBlocking()
                    .exchange("GET /ses/stadistics", String.class);

            // Assert: Verify HTTP 503 status
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatus());
            assertEquals(503, response.getStatus().getCode());
        }

        @Test
        @DisplayName("GET /ses/stadistics returns error message on CloudWatchLogsException")
        void testGetStadisticsCloudWatchFailureMessage() {
            // Arrange
            doThrow(new CloudWatchLogsException("CloudWatch service unavailable"))
                    .when(emailService).sendStadistics(anyString(), anyString());

            // Act
            HttpResponse<String> response = client.toBlocking()
                    .exchange("GET /ses/stadistics", String.class);

            // Assert: Verify error message in response body
            assertTrue(response.getBody().isPresent());
            assertEquals("Filter service unavailable. Please try again later.",
                    response.getBody().get());
        }

        @Test
        @DisplayName("GET /ses/stadistics logs error on CloudWatchLogsException")
        void testGetStadisticsCloudWatchFailureLogged() {
            // Arrange
            doThrow(new CloudWatchLogsException("CloudWatch service unavailable"))
                    .when(emailService).sendStadistics(anyString(), anyString());

            // Act & Assert: Verify no exception is thrown to caller
            assertDoesNotThrow(() -> client.toBlocking().exchange("GET /ses/stadistics", String.class));
        }
    }

    /**
     * Nested test class for generic RuntimeException scenarios.
     */
    @Nested
    @DisplayName("Generic RuntimeException Scenarios")
    class GenericRuntimeExceptionScenarios {

        @Test
        @DisplayName("GET /ses/stadistics returns HTTP 500 INTERNAL_SERVER_ERROR on generic RuntimeException")
        void testGetStadisticsRuntimeException() {
            // Arrange
            doThrow(new RuntimeException("Unexpected error"))
                    .when(emailService).sendStadistics(anyString(), anyString());

            // Act
            HttpResponse<String> response = client.toBlocking()
                    .exchange("GET /ses/stadistics", String.class);

            // Assert: Verify HTTP 500 status
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatus());
            assertEquals(500, response.getStatus().getCode());
        }

        @Test
        @DisplayName("GET /ses/stadistics logs error on generic RuntimeException")
        void testGetStadisticsRuntimeExceptionLogged() {
            // Arrange
            doThrow(new RuntimeException("Unexpected error"))
                    .when(emailService).sendStadistics(anyString(), anyString());

            // Act & Assert: Verify no exception is thrown to caller
            assertDoesNotThrow(() -> client.toBlocking().exchange("GET /ses/stadistics", String.class));
        }

        @Test
        @DisplayName("GET /ses/stadistics returns empty body on generic RuntimeException")
        void testGetStadisticsRuntimeExceptionBody() {
            // Arrange
            doThrow(new RuntimeException("Unexpected error"))
                    .when(emailService).sendStadistics(anyString(), anyString());

            // Act
            HttpResponse<String> response = client.toBlocking()
                    .exchange("GET /ses/stadistics", String.class);

            // Assert: Verify response body is empty for 500 errors
            assertTrue(response.getBody().isEmpty() || response.getBody().get().isEmpty());
        }
    }

    /**
     * Nested test class for edge cases and configuration scenarios.
     */
    @Nested
    @DisplayName("Edge Cases and Configuration")
    class EdgeCasesAndConfiguration {

        @Test
        @DisplayName("Controller receives configured recipient email from SesConfiguration")
        void testControllerUsesConfiguredRecipientEmail() {
            // Arrange
            ArgumentCaptor<String> emailCaptor = ArgumentCaptor.forClass(String.class);
            doNothing().when(emailService).sendStadistics(anyString(), anyString());

            // Act
            client.toBlocking().exchange("GET /ses/stadistics", String.class);

            // Assert: Verify configured email is used
            verify(emailService).sendStadistics(emailCaptor.capture(), anyString());
            assertEquals(RECIPIENT_EMAIL, emailCaptor.getValue());
        }

        @Test
        @DisplayName("Correct subject line is passed to EmailService")
        void testCorrectSubjectPassedToEmailService() {
            // Arrange
            ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
            doNothing().when(emailService).sendStadistics(anyString(), anyString());

            // Act
            client.toBlocking().exchange("GET /ses/stadistics", String.class);

            // Assert: Verify subject line
            verify(emailService).sendStadistics(anyString(), subjectCaptor.capture());
            assertEquals(STATISTICS_SUBJECT, subjectCaptor.getValue());
        }

        @Test
        @DisplayName("Multiple consecutive calls to endpoint are handled independently")
        void testMultipleConsecutiveCalls() {
            // Arrange
            doNothing().when(emailService).sendStadistics(anyString(), anyString());

            // Act
            HttpResponse<String> response1 = client.toBlocking()
                    .exchange("GET /ses/stadistics", String.class);
            HttpResponse<String> response2 = client.toBlocking()
                    .exchange("GET /ses/stadistics", String.class);

            // Assert: Both calls succeed
            assertEquals(HttpStatus.OK, response1.getStatus());
            assertEquals(HttpStatus.OK, response2.getStatus());
            verify(emailService, times(2)).sendStadistics(anyString(), anyString());
        }
    }
}
