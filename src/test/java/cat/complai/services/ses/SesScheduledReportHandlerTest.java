package cat.complai.services.ses;

import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SesScheduledReportHandler Unit Tests")
class SesScheduledReportHandlerTest {

    @Test
    @DisplayName("execute() delegates to MultiCitySesService")
    void execute_delegatesToMultiCityService() throws Exception {
        MultiCitySesService multiCityService = Mockito.mock(MultiCitySesService.class);
        Mockito.when(multiCityService.runReportsForAllCities()).thenReturn("OK: all reports sent");

        SesScheduledReportHandler handler = Mockito.mock(
                SesScheduledReportHandler.class, Mockito.CALLS_REAL_METHODS);

        Field field = SesScheduledReportHandler.class.getDeclaredField("multiCityService");
        field.setAccessible(true);
        field.set(handler, multiCityService);

        ScheduledEvent event = new ScheduledEvent();
        String result = handler.execute(event);

        assertEquals("OK: all reports sent", result);
    }

    @Test
    @DisplayName("execute() returns the result from multiCityService")
    void execute_returnsMultiCityServiceResult() throws Exception {
        MultiCitySesService multiCityService = Mockito.mock(MultiCitySesService.class);
        String expected = "Completed: 1 succeeded, 0 failed out of 1 cities\nelprat: OK: report sent";
        Mockito.when(multiCityService.runReportsForAllCities()).thenReturn(expected);

        SesScheduledReportHandler handler = Mockito.mock(
                SesScheduledReportHandler.class, Mockito.CALLS_REAL_METHODS);

        Field field = SesScheduledReportHandler.class.getDeclaredField("multiCityService");
        field.setAccessible(true);
        field.set(handler, multiCityService);

        ScheduledEvent event = new ScheduledEvent();
        String result = handler.execute(event);

        assertEquals(expected, result);
    }
}
