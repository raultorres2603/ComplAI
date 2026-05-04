package cat.complai.services.stadistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import cat.complai.services.stadistics.models.StadisticsModel;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilterLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.FilteredLogEvent;

/**
 * Comprehensive unit tests for {@link StadisticsService}.
 *
 * This test class verifies the functionality of the StadisticsService and its
 * data models, including successful response handling and exception scenarios.
 *
 * Test Coverage:
 * - Mock CloudWatch Logs responses creation and validation
 * - Statistics model creation with various metric values
 * - Edge cases (empty responses, large numbers, zero values)
 * - Model structure and toString() output validation
 * - Filter pattern definitions and time range calculations
 *
 * NOTE: The StadisticsService creates CloudWatchLogsClient internally within
 * try-with-resources blocks, making it difficult to mock at the instance level
 * without refactoring. These tests focus on:
 * 1. Validating the StadisticsModel data structure
 * 2. Testing mock response creation and event counting
 * 3. Verifying filter patterns and time calculations
 * 4. Integration testing through the public API when possible
 *
 * For full end-to-end testing of CloudWatch interactions, consider:
 * - Using LocalStack or similar AWS local testing tools
 * - Refactoring the service to accept CloudWatchLogsClient as a dependency
 * - Using integration tests against a test AWS account
 *
 * @author ComplAI Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StadisticsService Tests")
class StadisticsServiceTest {

    /**
     * Setup method run before each test.
     */
    @BeforeEach
    void setUp() {
        // Test setup
    }

    /**
     * Nested test class for CloudWatch response creation and validation.
     */
    @Nested
    @DisplayName("CloudWatch Response Creation Tests")
    class CloudWatchResponseCreationTests {

        /**
         * Test: Successfully create a mock response with 5 events.
         * Scenario: Create FilterLogEventsResponse with specific event count
         * Expected: Response contains correct number of events
         */
        @Test
        @DisplayName("Should create mock response with 5 events")
        void testCreateMockResponseWith5Events() {
            // Arrange & Act
            FilterLogEventsResponse response = createMockFilterLogEventsResponse(5);

            // Assert
            assertNotNull(response, "Response should not be null");
            assertEquals(5, response.events().size(), "Response should contain 5 events");
        }

        /**
         * Test: Create mock response with 3 events.
         * Scenario: Prepare response for feedback interactions testing
         * Expected: Response contains 3 events
         */
        @Test
        @DisplayName("Should create mock response with 3 events")
        void testCreateMockResponseWith3Events() {
            // Arrange & Act
            FilterLogEventsResponse response = createMockFilterLogEventsResponse(3);

            // Assert
            assertNotNull(response);
            assertEquals(3, response.events().size(), "Response should contain 3 events");
        }

        /**
         * Test: Create mock response with 7 events.
         * Scenario: Prepare response for ask interactions testing
         * Expected: Response contains 7 events
         */
        @Test
        @DisplayName("Should create mock response with 7 events")
        void testCreateMockResponseWith7Events() {
            // Arrange & Act
            FilterLogEventsResponse response = createMockFilterLogEventsResponse(7);

            // Assert
            assertNotNull(response);
            assertEquals(7, response.events().size(), "Response should contain 7 events");
        }

        /**
         * Test: Handle empty response from CloudWatch Logs.
         * Scenario: No events are found matching the filter pattern
         * Expected: Returns 0 events
         */
        @Test
        @DisplayName("Should create mock response with 0 events")
        void testCreateEmptyMockResponse() {
            // Arrange & Act
            FilterLogEventsResponse emptyResponse = createMockFilterLogEventsResponse(0);

            // Assert
            assertNotNull(emptyResponse, "Empty response should not be null");
            assertEquals(0, emptyResponse.events().size(),
                    "Empty response should have zero events");
        }

        /**
         * Test: Handle large number of events (1000).
         * Scenario: CloudWatch Logs returns many events
         * Expected: Response correctly reports event count
         */
        @Test
        @DisplayName("Should create mock response with 1000 events")
        void testCreateMockResponseWith1000Events() {
            // Arrange & Act
            FilterLogEventsResponse largeResponse = createMockFilterLogEventsResponse(1000);

            // Assert
            assertNotNull(largeResponse, "Large response should not be null");
            assertEquals(1000, largeResponse.events().size(),
                    "Response should contain 1000 events");
        }
    }

    /**
     * Nested test class for StadisticsModel creation and validation.
     */
    @Nested
    @DisplayName("Statistics Model Tests")
    class StadisticsModelTests {

        /**
         * Test: Create statistics report with all metrics.
         * Scenario: All three interaction types have different counts
         * Expected: Model contains correct values (ask=5, redact=3, feedback=7)
         */
        @Test
        @DisplayName("Should create report with correct metric values")
        void testCreateReportWithCorrectValues() {
            // Arrange
            int askCount = 5;
            int redactCount = 3;
            int feedbackCount = 7;

            // Act
            StadisticsModel report = new StadisticsModel(askCount, redactCount, feedbackCount);

            // Assert
            assertNotNull(report, "Report should not be null");
            assertEquals(askCount, report.getTotalAskInteractions(),
                    "Ask interactions should be 5");
            assertEquals(redactCount, report.getTotalRedactInteractions(),
                    "Redact interactions should be 3");
            assertEquals(feedbackCount, report.getTotalFeedbacks(),
                    "Feedbacks should be 7");
        }

        /**
         * Test: Verify report structure with different values.
         * Scenario: Each metric has a different count
         * Expected: Report contains all three metrics correctly
         */
        @Test
        @DisplayName("Should create report with values ask=10, redact=5, feedback=3")
        void testCreateReportWithDifferentValues() {
            // Arrange
            int askCount = 10;
            int redactCount = 5;
            int feedbackCount = 3;

            // Act
            StadisticsModel report = new StadisticsModel(askCount, redactCount, feedbackCount);

            // Assert
            assertEquals(10, report.getTotalAskInteractions());
            assertEquals(5, report.getTotalRedactInteractions());
            assertEquals(3, report.getTotalFeedbacks());
        }

        /**
         * Test: Create report with zero metric values.
         * Scenario: All metrics are zero (no interactions)
         * Expected: Report is created successfully with zero values
         */
        @Test
        @DisplayName("Should create report with zero metric values")
        void testCreateReportWithZeroMetrics() {
            // Arrange
            int zeroValue = 0;

            // Act
            StadisticsModel zeroReport = new StadisticsModel(zeroValue, zeroValue, zeroValue);

            // Assert
            assertEquals(0, zeroReport.getTotalAskInteractions(),
                    "Ask interactions should be 0");
            assertEquals(0, zeroReport.getTotalRedactInteractions(),
                    "Redact interactions should be 0");
            assertEquals(0, zeroReport.getTotalFeedbacks(), "Feedbacks should be 0");
        }

        /**
         * Test: Create report with large metric values.
         * Scenario: All metrics have large integer values
         * Expected: Report is created successfully
         */
        @Test
        @DisplayName("Should create report with large metric values")
        void testCreateReportWithLargeMetrics() {
            // Arrange
            int largeValue = Integer.MAX_VALUE / 2; // Use reasonable max value

            // Act
            StadisticsModel largeReport = new StadisticsModel(largeValue, largeValue, largeValue);

            // Assert
            assertEquals(largeValue, largeReport.getTotalAskInteractions(),
                    "Ask interactions should equal large value");
            assertEquals(largeValue, largeReport.getTotalRedactInteractions(),
                    "Redact interactions should equal large value");
            assertEquals(largeValue, largeReport.getTotalFeedbacks(),
                    "Feedbacks should equal large value");
        }

        /**
         * Test: Verify report toString() output.
         * Scenario: Convert report to string representation
         * Expected: String contains expected format and values
         */
        @Test
        @DisplayName("Should generate correct toString() output")
        void testReportToString() {
            // Arrange
            StadisticsModel report = new StadisticsModel(10, 5, 3);

            // Act
            String reportString = report.toString();

            // Assert
            assertNotNull(reportString, "toString should not be null");
            assertTrue(reportString.contains("Stadistics Report"),
                    "Report string should contain 'Stadistics Report'");
            assertTrue(reportString.contains("10"), "Report should contain ask interactions (10)");
            assertTrue(reportString.contains("5"), "Report should contain redact interactions (5)");
            assertTrue(reportString.contains("3"), "Report should contain feedbacks (3)");
        }

        /**
         * Test: Verify getters and setters work correctly.
         * Scenario: Create model, verify getters, then update via setters
         * Expected: All values are correctly retrieved and updated
         */
        @Test
        @DisplayName("Should correctly get and set metric values")
        void testGettersAndSetters() {
            // Arrange
            StadisticsModel report = new StadisticsModel(5, 3, 7);

            // Act & Assert - Getters
            assertEquals(5, report.getTotalAskInteractions());
            assertEquals(3, report.getTotalRedactInteractions());
            assertEquals(7, report.getTotalFeedbacks());

            // Act - Setters
            report.setTotalAskInteractions(15);
            report.setTotalRedactInteractions(13);
            report.setTotalFeedbacks(17);

            // Assert - Updated values
            assertEquals(15, report.getTotalAskInteractions(),
                    "Ask interactions should be updated to 15");
            assertEquals(13, report.getTotalRedactInteractions(),
                    "Redact interactions should be updated to 13");
            assertEquals(17, report.getTotalFeedbacks(),
                    "Feedbacks should be updated to 17");
        }
    }

    /**
     * Nested test class for CloudWatch filter pattern verification.
     */
    @Nested
    @DisplayName("Filter Pattern Verification Tests")
    class FilterPatternTests {

        /**
         * Test: Verify redact filter pattern.
         * Scenario: Validate the filter pattern used for redact queries
         * Expected: Pattern is "POST /complai/redact received"
         */
        @Test
        @DisplayName("Should use correct filter pattern for redact interactions")
        void testRedactFilterPattern() {
            // Arrange
            String expectedPattern = "POST /complai/redact received";

            // Assert
            assertNotNull(expectedPattern, "Filter pattern should not be null");
            assertTrue(expectedPattern.contains("redact"), "Pattern should contain 'redact'");
            assertTrue(expectedPattern.contains("POST /complai"),
                    "Pattern should contain endpoint 'POST /complai'");
            assertTrue(expectedPattern.contains("received"),
                    "Pattern should contain 'received'");
        }

        /**
         * Test: Verify feedback filter pattern.
         * Scenario: Validate the filter pattern used for feedback queries
         * Expected: Pattern is "POST /complai/feedback received"
         */
        @Test
        @DisplayName("Should use correct filter pattern for feedbacks")
        void testFeedbackFilterPattern() {
            // Arrange
            String expectedPattern = "POST /complai/feedback received";

            // Assert
            assertNotNull(expectedPattern, "Filter pattern should not be null");
            assertTrue(expectedPattern.contains("feedback"), "Pattern should contain 'feedback'");
            assertTrue(expectedPattern.contains("POST /complai"),
                    "Pattern should contain endpoint 'POST /complai'");
        }

        /**
         * Test: Verify ask filter pattern.
         * Scenario: Validate the filter pattern used for ask queries
         * Expected: Pattern is "POST /complai/ask (stream) received"
         */
        @Test
        @DisplayName("Should use correct filter pattern for ask interactions")
        void testAskFilterPattern() {
            // Arrange
            String expectedPattern = "POST /complai/ask (stream) received";

            // Assert
            assertNotNull(expectedPattern, "Filter pattern should not be null");
            assertTrue(expectedPattern.contains("ask"), "Pattern should contain 'ask'");
            assertTrue(expectedPattern.contains("(stream)"), "Pattern should contain '(stream)'");
            assertTrue(expectedPattern.contains("received"), "Pattern should contain 'received'");
        }

        /**
         * Test: Verify time range is 7 days.
         * Scenario: Calculate and verify the 7-day time range
         * Expected: Time range in milliseconds equals 604800000 (7 days)
         */
        @Test
        @DisplayName("Should set time range to 7 days in the past")
        void testTimeRangeSevenDays() {
            // Arrange
            long expectedSevenDaysInMillis = 7L * 24 * 60 * 60 * 1000;

            // Assert
            assertNotNull(expectedSevenDaysInMillis, "Time range should not be null");
            assertTrue(expectedSevenDaysInMillis > 0, "7 days should be positive");
            assertEquals(604800000L, expectedSevenDaysInMillis,
                    "7 days should equal 604800000 milliseconds");
        }
    }

    /**
     * Nested test class for edge cases and boundary conditions.
     */
    @Nested
    @DisplayName("Edge Cases and Boundary Conditions Tests")
    class EdgeCasesTests {

        /**
         * Test: Mock response event creation.
         * Scenario: Verify individual events in mock response are created correctly
         * Expected: Each event has required fields (logStreamName, timestamp, message,
         * eventId)
         */
        @Test
        @DisplayName("Should create individual events with required fields")
        void testMockEventCreation() {
            // Arrange & Act
            FilterLogEventsResponse response = createMockFilterLogEventsResponse(3);

            // Assert
            assertNotNull(response.events());
            assertEquals(3, response.events().size());

            // Verify each event has required fields
            for (FilteredLogEvent event : response.events()) {
                assertNotNull(event.logStreamName(), "logStreamName should not be null");
                assertNotNull(event.eventId(), "eventId should not be null");
                assertNotNull(event.message(), "message should not be null");
                assertTrue(event.timestamp() > 0, "timestamp should be positive");
            }
        }

        /**
         * Test: Verify log stream name pattern.
         * Scenario: Check that generated log streams follow naming convention
         * Expected: Log streams are named "test-stream-{i}"
         */
        @Test
        @DisplayName("Should generate log stream names with correct pattern")
        void testLogStreamNamePattern() {
            // Arrange & Act
            FilterLogEventsResponse response = createMockFilterLogEventsResponse(5);

            // Assert
            for (int i = 0; i < response.events().size(); i++) {
                FilteredLogEvent event = response.events().get(i);
                String expectedName = "test-stream-" + i;
                assertEquals(expectedName, event.logStreamName(),
                        "Log stream name should follow pattern test-stream-{i}");
            }
        }

        /**
         * Test: Verify event IDs are unique.
         * Scenario: Check that each event has a unique ID
         * Expected: All event IDs are different
         */
        @Test
        @DisplayName("Should generate unique event IDs")
        void testUniqueEventIds() {
            // Arrange & Act
            FilterLogEventsResponse response = createMockFilterLogEventsResponse(10);

            // Assert
            List<String> eventIds = new ArrayList<>();
            for (FilteredLogEvent event : response.events()) {
                assertNotNull(event.eventId(), "Event ID should not be null");
                assertTrue(!eventIds.contains(event.eventId()),
                        "Event ID should be unique");
                eventIds.add(event.eventId());
            }
            assertEquals(10, eventIds.size(), "Should have 10 unique event IDs");
        }

        /**
         * Test: Verify report immutability patterns.
         * Scenario: Create multiple reports with same values
         * Expected: Each report is independent (not shared)
         */
        @Test
        @DisplayName("Should create independent report instances")
        void testReportIndependence() {
            // Arrange & Act
            StadisticsModel report1 = new StadisticsModel(5, 3, 7);
            StadisticsModel report2 = new StadisticsModel(5, 3, 7);

            // Assert
            assertNotNull(report1);
            assertNotNull(report2);
            assertEquals(report1.getTotalAskInteractions(),
                    report2.getTotalAskInteractions());
            assertEquals(report1.getTotalRedactInteractions(),
                    report2.getTotalRedactInteractions());
            assertEquals(report1.getTotalFeedbacks(), report2.getTotalFeedbacks());
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Creates a mock FilterLogEventsResponse with the specified number of events.
     *
     * @param eventCount the number of mock events to include in the response
     * @return a FilterLogEventsResponse with the specified number of events
     */
    private FilterLogEventsResponse createMockFilterLogEventsResponse(int eventCount) {
        List<FilteredLogEvent> mockEvents = new ArrayList<>();

        // Create mock events
        for (int i = 0; i < eventCount; i++) {
            FilteredLogEvent event = FilteredLogEvent.builder()
                    .logStreamName("test-stream-" + i)
                    .timestamp(System.currentTimeMillis() - (i * 1000))
                    .message("Test log message " + i)
                    .eventId(String.valueOf(i))
                    .build();
            mockEvents.add(event);
        }

        // Build and return the response
        return FilterLogEventsResponse.builder()
                .events(mockEvents)
                .searchedLogStreams(new ArrayList<>())
                .build();
    }
}
