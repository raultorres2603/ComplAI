package cat.complai.utilities.logging;

import cat.complai.utilities.auth.ApiKeyAuthFilter;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CityIdLoggingFilterTest {

    private final CityIdLoggingFilter filter = new CityIdLoggingFilter();

    @Mock
    private MutableHttpRequest<?> request;

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void filterReturnsNull() {
        when(request.getAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, String.class))
                .thenReturn(Optional.empty());
        assertNull(filter.filter(request));
    }

    @Test
    void filterWithCityId_setsMdc() {
        when(request.getAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, String.class))
                .thenReturn(Optional.of("elprat"));

        assertNull(filter.filter(request));
        assertEquals("elprat", MDC.get("cityId"));
    }

    @Test
    void filterWithoutCityId_mdcIsEmpty() {
        when(request.getAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, String.class))
                .thenReturn(Optional.empty());

        filter.filter(request);
        assertNull(MDC.get("cityId"));
    }

    @Test
    void filterClearsPreviousMdcBeforeSettingNewValue() {
        MDC.put("cityId", "oldcity");

        when(request.getAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, String.class))
                .thenReturn(Optional.of("newcity"));

        filter.filter(request);
        assertEquals("newcity", MDC.get("cityId"));
    }

    @Test
    void filterWithCityIdClearsPreviousMdcWhenNewCityIsBlank() {
        MDC.put("cityId", "oldcity");

        when(request.getAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, String.class))
                .thenReturn(Optional.of("   "));

        filter.filter(request);
        assertNull(MDC.get("cityId"));
    }
}
