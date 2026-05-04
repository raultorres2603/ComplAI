package cat.complai.services.ses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import cat.complai.config.SesConfiguration;
import cat.complai.exceptions.ses.SesEmailException;
import cat.complai.services.stadistics.StadisticsService;
import cat.complai.services.stadistics.models.StadisticsModel;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.MailFromDomainNotVerifiedException;
import software.amazon.awssdk.services.ses.model.MessageRejectedException;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

/**
 * Unit tests for {@link EmailService}.
 *
 * <p>
 * Tests cover validation, success cases, exception handling, and statistics
 * integration.
 * Uses Mockito to mock SES client dependencies and verify email sending
 * behavior.
 * 
 * @author ComplAI Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailService Unit Tests")
class EmailServiceTest {

    private static final String TEST_FROM_EMAIL = "noreply@complai.test";
    private static final String TEST_TO_EMAIL = "admin@complai.test";
    private static final String TEST_SUBJECT = "Statistics Report";
    private static final String TEST_MESSAGE_ID = "test-message-id-12345";

    @Mock
    private SesClient sesClient;

    @Mock
    private SesConfiguration sesConfiguration;

    @Mock
    private StadisticsService stadisticsService;

    private EmailService emailService;

    /**
     * Initializes the EmailService with mocked dependencies before each test.
     * Uses reflection to inject mocked SesClient and StadisticsService since they
     * are private fields.
     */
    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        when(sesConfiguration.getFromEmail()).thenReturn(TEST_FROM_EMAIL);
        when(sesConfiguration.getRegion()).thenReturn("eu-west-1");

        // Create EmailService with the mocked SES configuration
        emailService = new EmailService(sesConfiguration);

        // Inject mocked dependencies using reflection since they are private fields
        injectField(emailService, "sesClient", sesClient);
        injectField(emailService, "stadisticsService", stadisticsService);
    }

    /**
     * Helper method to inject values into private fields using reflection.
     */
    private void injectField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * Nested test class for constructor validation tests.
     */
    @Nested
    @DisplayName("Constructor Validation Tests")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Constructor throws IllegalArgumentException when SesConfiguration is null")
        void testConstructorThrowsExceptionWhenSesConfigurationIsNull() {
            assertThrows(IllegalArgumentException.class, () -> {
                new EmailService(null);
            });
        }

        @Test
        @DisplayName("Constructor throws IllegalArgumentException when fromEmail is null")
        void testConstructorThrowsExceptionWhenFromEmailIsNull() {
            SesConfiguration mockConfig = org.mockito.Mockito.mock(SesConfiguration.class);
            try {
                MockitoAnnotations.openMocks(this);
                when(mockConfig.getFromEmail()).thenReturn(null);

                assertThrows(IllegalArgumentException.class, () -> {
                    new EmailService(mockConfig);
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("Constructor throws IllegalArgumentException when fromEmail is blank")
        void testConstructorThrowsExceptionWhenFromEmailIsBlank() {
            SesConfiguration mockConfig = org.mockito.Mockito.mock(SesConfiguration.class);
            try {
                MockitoAnnotations.openMocks(this);
                when(mockConfig.getFromEmail()).thenReturn("   ");

                assertThrows(IllegalArgumentException.class, () -> {
                    new EmailService(mockConfig);
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Nested test class for input validation tests.
     */
    @Nested
    @DisplayName("Input Validation Tests")
    class InputValidationTests {

        @Test
        @DisplayName("sendStadistics throws IllegalArgumentException when 'to' is null")
        void testSendStadisticsWithNullToThrowsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> {
                emailService.sendStadistics(null, TEST_SUBJECT);
            });
        }

        @Test
        @DisplayName("sendStadistics throws IllegalArgumentException when 'to' is empty")
        void testSendStadisticsWithEmptyToThrowsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> {
                emailService.sendStadistics("", TEST_SUBJECT);
            });
        }

        @Test
        @DisplayName("sendStadistics throws IllegalArgumentException when 'to' is blank")
        void testSendStadisticsWithBlankToThrowsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> {
                emailService.sendStadistics("   ", TEST_SUBJECT);
            });
        }

        @Test
        @DisplayName("sendStadistics throws IllegalArgumentException when 'subject' is null")
        void testSendStadisticsWithNullSubjectThrowsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> {
                emailService.sendStadistics(TEST_TO_EMAIL, null);
            });
        }

        @Test
        @DisplayName("sendStadistics throws IllegalArgumentException when 'subject' is empty")
        void testSendStadisticsWithEmptySubjectThrowsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> {
                emailService.sendStadistics(TEST_TO_EMAIL, "");
            });
        }

        @Test
        @DisplayName("sendStadistics throws IllegalArgumentException when 'subject' is blank")
        void testSendStadisticsWithBlankSubjectThrowsIllegalArgumentException() {
            assertThrows(IllegalArgumentException.class, () -> {
                emailService.sendStadistics(TEST_TO_EMAIL, "   ");
            });
        }
    }

    // ==================== Success Case ====================

    @Test
    void testSendStadisticsSuccessfulEmailSending() {
        // Arrange
        SendEmailResponse mockResponse = SendEmailResponse.builder()
                .messageId(TEST_MESSAGE_ID)
                .build();

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(mockResponse);

        StadisticsModel mockStadistics = new StadisticsModel(10, 5, 3);
        when(stadisticsService.generateStadisticsReport())
                .thenReturn(mockStadistics);

        // Act
        emailService.sendStadistics(TEST_TO_EMAIL, TEST_SUBJECT);

        // Assert
        verify(sesClient, times(1)).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void testSendStadisticsVerifiesEmailRecipient() {
        // Arrange
        SendEmailResponse mockResponse = SendEmailResponse.builder()
                .messageId(TEST_MESSAGE_ID)
                .build();

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(mockResponse);

        StadisticsModel mockStadistics = new StadisticsModel(10, 5, 3);
        when(stadisticsService.generateStadisticsReport())
                .thenReturn(mockStadistics);

        ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);

        // Act
        emailService.sendStadistics(TEST_TO_EMAIL, TEST_SUBJECT);

        // Assert
        verify(sesClient).sendEmail(requestCaptor.capture());
        SendEmailRequest capturedRequest = requestCaptor.getValue();

        assertNotNull(capturedRequest.destination(), "Destination must not be null");
        assertTrue(capturedRequest.destination().toAddresses().contains(TEST_TO_EMAIL),
                "Email recipient must be " + TEST_TO_EMAIL);
    }

    @Test
    void testSendStadisticsVerifiesSubjectAndSource() {
        // Arrange
        SendEmailResponse mockResponse = SendEmailResponse.builder()
                .messageId(TEST_MESSAGE_ID)
                .build();

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(mockResponse);

        StadisticsModel mockStadistics = new StadisticsModel(10, 5, 3);
        when(stadisticsService.generateStadisticsReport())
                .thenReturn(mockStadistics);

        ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);

        // Act
        emailService.sendStadistics(TEST_TO_EMAIL, TEST_SUBJECT);

        // Assert
        verify(sesClient).sendEmail(requestCaptor.capture());
        SendEmailRequest capturedRequest = requestCaptor.getValue();

        assertNotNull(capturedRequest.message(), "Message must not be null");
        assertEquals(TEST_SUBJECT, capturedRequest.message().subject().data(),
                "Subject must match: " + TEST_SUBJECT);
        assertEquals(TEST_FROM_EMAIL, capturedRequest.source(),
                "Source must be: " + TEST_FROM_EMAIL);
    }

    // ==================== Exception Cases ====================

    @Test
    void testSendStadisticsMessageRejectedException() {
        // Arrange
        StadisticsModel mockStadistics = new StadisticsModel(10, 5, 3);
        when(stadisticsService.generateStadisticsReport())
                .thenReturn(mockStadistics);

        AwsErrorDetails errorDetails = AwsErrorDetails.builder()
                .errorMessage("Email address not verified")
                .build();

        MessageRejectedException exception = (MessageRejectedException) MessageRejectedException.builder()
                .awsErrorDetails(errorDetails)
                .message("Email address not verified")
                .build();

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(exception);

        // Act & Assert
        SesEmailException thrownException = assertThrows(SesEmailException.class, () -> {
            emailService.sendStadistics(TEST_TO_EMAIL, TEST_SUBJECT);
        });

        assertNotNull(thrownException.getMessage());
        assertTrue(thrownException.getMessage().contains("Email address not verified"),
                "Exception message must contain error details");
        assertSame(exception, thrownException.getCause());
    }

    @Test
    void testSendStadisticsMailFromDomainNotVerifiedException() {
        // Arrange
        StadisticsModel mockStadistics = new StadisticsModel(10, 5, 3);
        when(stadisticsService.generateStadisticsReport())
                .thenReturn(mockStadistics);

        MailFromDomainNotVerifiedException exception = (MailFromDomainNotVerifiedException) MailFromDomainNotVerifiedException
                .builder()
                .message("Sender domain is not verified")
                .build();

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(exception);

        // Act & Assert
        SesEmailException thrownException = assertThrows(SesEmailException.class, () -> {
            emailService.sendStadistics(TEST_TO_EMAIL, TEST_SUBJECT);
        });

        assertNotNull(thrownException.getMessage());
        assertTrue(thrownException.getMessage().contains("not verified"),
                "Exception message must indicate verification issue");
        assertSame(exception, thrownException.getCause());
    }

    @Test
    void testSendStadisticsGenericException() {
        // Arrange
        StadisticsModel mockStadistics = new StadisticsModel(10, 5, 3);
        when(stadisticsService.generateStadisticsReport())
                .thenReturn(mockStadistics);

        RuntimeException genericException = new RuntimeException("Network error occurred");

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(genericException);

        // Act & Assert
        SesEmailException thrownException = assertThrows(SesEmailException.class, () -> {
            emailService.sendStadistics(TEST_TO_EMAIL, TEST_SUBJECT);
        });

        assertNotNull(thrownException.getMessage());
        assertTrue(thrownException.getMessage().contains("Network error occurred"),
                "Exception message must contain original error");
        assertSame(genericException, thrownException.getCause());
    }

    // ==================== Statistics Integration Tests ====================

    @Test
    void testSendStadisticsCallsStatisticsService() {
        // Arrange
        SendEmailResponse mockResponse = SendEmailResponse.builder()
                .messageId(TEST_MESSAGE_ID)
                .build();

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(mockResponse);

        StadisticsModel mockStadistics = new StadisticsModel(10, 5, 3);
        when(stadisticsService.generateStadisticsReport())
                .thenReturn(mockStadistics);

        // Act
        emailService.sendStadistics(TEST_TO_EMAIL, TEST_SUBJECT);

        // Assert
        verify(stadisticsService, times(1)).generateStadisticsReport();
    }

    @Test
    void testSendStadisticsIncludesStatisticsReportInEmailBody() {
        // Arrange
        SendEmailResponse mockResponse = SendEmailResponse.builder()
                .messageId(TEST_MESSAGE_ID)
                .build();

        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenReturn(mockResponse);

        String expectedStadisticsContent = "Statistics: 10 queries, 5 feedback";
        StadisticsModel mockStadistics = new StadisticsModel(10, 5, 3);

        // Mock the stadisticsService to return our mock with the expected content
        when(stadisticsService.generateStadisticsReport())
                .thenReturn(mockStadistics);

        ArgumentCaptor<SendEmailRequest> requestCaptor = ArgumentCaptor.forClass(SendEmailRequest.class);

        // Act
        emailService.sendStadistics(TEST_TO_EMAIL, TEST_SUBJECT);

        // Assert
        verify(sesClient).sendEmail(requestCaptor.capture());
        SendEmailRequest capturedRequest = requestCaptor.getValue();

        assertNotNull(capturedRequest.message().body(), "Email body must not be null");
        assertNotNull(capturedRequest.message().body().html(), "Email HTML body must not be null");

        // Verify the statistics report is included in the email body
        String emailBodyContent = capturedRequest.message().body().html().data();
        assertTrue(emailBodyContent.contains("Stadistics Report"),
                "Email body must contain 'Stadistics Report'");
        assertTrue(emailBodyContent.contains("Total Ask Interactions: 10"),
                "Email body must contain total ask interactions");
        assertTrue(emailBodyContent.contains("Total Feedbacks: 3"),
                "Email body must contain total feedbacks");
    }
}
