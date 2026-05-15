package cat.complai.services.ses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cat.complai.services.stadistics.IStadisticsService;

@DisplayName("MultiCitySesService Unit Tests")
class MultiCitySesServiceTest {

    private static final String CITY = "testcity";
    private static final String EMAIL = "test@example.com";

    private IEmailService emailService;
    private IStadisticsService stadisticsService;
    private MultiCitySesService service;

    @BeforeEach
    void setUp() {
        emailService = mock(IEmailService.class);
        stadisticsService = mock(IStadisticsService.class);
        service = new MultiCitySesService(emailService, stadisticsService);
    }

    private void setConfiguredCities(Map<String, String> cities) throws Exception {
        Field field = MultiCitySesService.class.getDeclaredField("configuredCities");
        field.setAccessible(true);
        field.set(service, cities);
    }

    @Nested
    @DisplayName("No cities configured")
    class NoCitiesConfigured {

        @Test
        @DisplayName("getConfiguredCities returns empty list")
        void getConfiguredCities_returnsEmpty() {
            assertTrue(service.getConfiguredCities().isEmpty());
        }

        @Test
        @DisplayName("getRecipientEmail returns null")
        void getRecipientEmail_returnsNull() {
            assertNull(service.getRecipientEmail(CITY));
        }

        @Test
        @DisplayName("isCityConfigured returns false")
        void isCityConfigured_returnsFalse() {
            assertFalse(service.isCityConfigured(CITY));
        }

        @Test
        @DisplayName("runReportForCity returns error when no recipient email")
        void runReportForCity_noRecipient_returnsError() {
            String result = service.runReportForCity(CITY);
            assertEquals("ERROR: No recipient email for city " + CITY, result);
            verifyNoInteractions(stadisticsService, emailService);
        }

        @Test
        @DisplayName("runReportsForAllCities returns error when no cities configured")
        void runReportsForAllCities_noCities_returnsError() {
            String result = service.runReportsForAllCities();
            assertEquals("ERROR: No cities configured for SES reporting", result);
            verifyNoInteractions(stadisticsService, emailService);
        }
    }

    @Nested
    @DisplayName("With configured city")
    class WithConfiguredCity {

        @BeforeEach
        void setUp() throws Exception {
            setConfiguredCities(Map.of(CITY, EMAIL));
        }

        @Test
        @DisplayName("getConfiguredCities returns the configured city")
        void getConfiguredCities_returnsCity() {
            List<String> cities = service.getConfiguredCities();
            assertEquals(List.of(CITY), cities);
        }

        @Test
        @DisplayName("getRecipientEmail returns the configured email")
        void getRecipientEmail_returnsEmail() {
            assertEquals(EMAIL, service.getRecipientEmail(CITY));
        }

        @Test
        @DisplayName("getRecipientEmail returns null for unknown city")
        void getRecipientEmail_unknownCity_returnsNull() {
            assertNull(service.getRecipientEmail("unknown"));
        }

        @Test
        @DisplayName("isCityConfigured returns true for configured city")
        void isCityConfigured_configured_returnsTrue() {
            assertTrue(service.isCityConfigured(CITY));
        }

        @Test
        @DisplayName("isCityConfigured returns false for unknown city")
        void isCityConfigured_unknown_returnsFalse() {
            assertFalse(service.isCityConfigured("unknown"));
        }

        @Test
        @DisplayName("runReportForCity success returns OK with masked email")
        void runReportForCity_success_returnsOk() throws Exception {
            String result = service.runReportForCity(CITY);

            verify(stadisticsService).generateStadisticsReport(CITY);
            verify(emailService).sendStadistics(anyString(), anyString(), anyString());
            assertTrue(result.startsWith("OK:"));
            assertTrue(result.contains("t**@example.com"));
        }

        @Test
        @DisplayName("runReportForCity error returns ERROR with exception message")
        void runReportForCity_stadisticsThrows_returnsError() {
            when(stadisticsService.generateStadisticsReport(CITY))
                    .thenThrow(new RuntimeException("CloudWatch timeout"));

            String result = service.runReportForCity(CITY);

            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("CloudWatch timeout"));
        }

        @Test
        @DisplayName("runReportForCity email send failure returns ERROR")
        void runReportForCity_emailThrows_returnsError() {
            doThrow(new RuntimeException("SES failure"))
                    .when(emailService).sendStadistics(anyString(), anyString(), anyString());

            String result = service.runReportForCity(CITY);

            assertTrue(result.startsWith("ERROR:"));
            assertTrue(result.contains("SES failure"));
        }

        @Test
        @DisplayName("runReportsForAllCities returns success summary")
        void runReportsForAllCities_allSuccess_returnsSummary() {
            String result = service.runReportsForAllCities();

            assertTrue(result.contains("Completed: 1 succeeded, 0 failed out of 1 cities"));
            assertTrue(result.contains(CITY + ": OK:"));
            verify(stadisticsService).generateStadisticsReport(CITY);
            verify(emailService).sendStadistics(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("runReportsForAllCities reports failures in summary")
        void runReportsForAllCities_withFailures_returnsSummary() throws Exception {
            when(stadisticsService.generateStadisticsReport(CITY))
                    .thenThrow(new RuntimeException("fail"));

            String result = service.runReportsForAllCities();

            assertTrue(result.contains("Completed: 0 succeeded, 1 failed out of 1 cities"));
            assertTrue(result.contains(CITY + ": ERROR:"));
        }
    }

    @Nested
    @DisplayName("Multiple configured cities")
    class MultipleConfiguredCities {

        private static final String CITY_A = "city_a";
        private static final String CITY_B = "city_b";
        private static final String EMAIL_A = "alice@a.com";
        private static final String EMAIL_B = "bob@b.com";

        @BeforeEach
        void setUp() throws Exception {
            setConfiguredCities(Map.of(CITY_A, EMAIL_A, CITY_B, EMAIL_B));
        }

        @Test
        @DisplayName("getConfiguredCities returns all cities")
        void getConfiguredCities_returnsAll() {
            List<String> cities = service.getConfiguredCities();
            assertEquals(2, cities.size());
            assertTrue(cities.containsAll(List.of(CITY_A, CITY_B)));
        }

        @Test
        @DisplayName("runReportsForAllCities processes all cities with mixed results")
        void runReportsForAllCities_mixedResults_returnsSummary() {
            when(stadisticsService.generateStadisticsReport(CITY_B))
                    .thenThrow(new RuntimeException("fail"));

            String result = service.runReportsForAllCities();

            // Both cities must be processed
            verify(stadisticsService).generateStadisticsReport(CITY_A);
            verify(stadisticsService).generateStadisticsReport(CITY_B);
            // Only CITY_A succeeded and should have triggered email
            verify(emailService).sendStadistics(anyString(), anyString(), anyString());
            // Only CITY_A succeeded; CITY_B threw before reaching emailService
            assertTrue(result.contains("Completed: 1 succeeded, 1 failed out of 2 cities"),
                    "Expected 1 success + 1 failure. Got: " + result);
            assertTrue(result.contains(CITY_A + ": OK:"), "Expected OK for CITY_A. Got: " + result);
            assertTrue(result.contains(CITY_B + ": ERROR:"), "Expected ERROR for CITY_B. Got: " + result);
        }
    }

    @Nested
    @DisplayName("maskEmail behavior observed through runReportForCity output")
    class MaskEmailBehavior {

        @Test
        @DisplayName("masks typical email as f*@domain.com")
        void typicalEmail() throws Exception {
            setConfiguredCities(Map.of(CITY, "foo@domain.com"));

            String result = service.runReportForCity(CITY);

            assertTrue(result.contains("f*@domain.com"));
        }

        @Test
        @DisplayName("masks single-char local part as *@domain.com")
        void singleCharLocalPart() throws Exception {
            setConfiguredCities(Map.of(CITY, "a@b.com"));

            String result = service.runReportForCity(CITY);

            assertTrue(result.contains("*@b.com"));
        }

        @Test
        @DisplayName("masks two-char local part as f*@domain.com")
        void twoCharLocalPart() throws Exception {
            setConfiguredCities(Map.of(CITY, "fx@domain.com"));

            String result = service.runReportForCity(CITY);

            assertTrue(result.contains("f*@domain.com"));
        }

        @Test
        @DisplayName("handles null email as ***")
        void nullEmail() throws Exception {
            Map<String, String> map = new HashMap<>();
            map.put(CITY, null);
            setConfiguredCities(map);

            String result = service.runReportForCity(CITY);

            assertEquals("ERROR: No recipient email for city " + CITY, result);
        }
    }
}
