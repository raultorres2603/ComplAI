package cat.complai.helpers.openrouter;

import cat.complai.config.TelegramConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class RagWarmupServiceTest {

    private TelegramConfiguration mockTelegramConfig(Set<String> cities) {
        TelegramConfiguration config = Mockito.mock(TelegramConfiguration.class);
        when(config.getAllConfiguredCities()).thenReturn(cities);
        for (String city : cities) {
            when(config.getToken(city)).thenReturn("token-" + city);
            when(config.hasBotForCity(city)).thenReturn(true);
        }
        return config;
    }

    @Test
    void onStartup_warmsUpDefaultCity() throws Exception {
        ProcedureRagHelperRegistry procedureRegistry = Mockito.mock(ProcedureRagHelperRegistry.class);
        EventRagHelperRegistry eventRegistry = Mockito.mock(EventRagHelperRegistry.class);
        CityInfoRagHelperRegistry cityInfoRegistry = Mockito.mock(CityInfoRagHelperRegistry.class);
        TelegramConfiguration telegramConfig = mockTelegramConfig(Set.of());

        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                "elprat", telegramConfig);

        service.onStartup();

        verify(procedureRegistry, times(1)).getForCity("elprat");
        verify(eventRegistry, times(1)).getForCity("elprat");
        verify(cityInfoRegistry, times(1)).getForCity("elprat");
    }

    @Test
    void onStartup_warmsUpAllConfiguredCities() throws Exception {
        ProcedureRagHelperRegistry procedureRegistry = Mockito.mock(ProcedureRagHelperRegistry.class);
        EventRagHelperRegistry eventRegistry = Mockito.mock(EventRagHelperRegistry.class);
        CityInfoRagHelperRegistry cityInfoRegistry = Mockito.mock(CityInfoRagHelperRegistry.class);
        TelegramConfiguration telegramConfig = mockTelegramConfig(Set.of("elprat", "testcity"));

        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                "elprat", telegramConfig);

        service.onStartup();

        // Both cities should be warmed
        verify(procedureRegistry).getForCity("elprat");
        verify(procedureRegistry).getForCity("testcity");
        verify(eventRegistry).getForCity("elprat");
        verify(eventRegistry).getForCity("testcity");
        verify(cityInfoRegistry).getForCity("elprat");
        verify(cityInfoRegistry).getForCity("testcity");
    }

    @Test
    void onStartup_warmsOnlyTelegramCitiesWhenNoDefault() throws Exception {
        ProcedureRagHelperRegistry procedureRegistry = Mockito.mock(ProcedureRagHelperRegistry.class);
        EventRagHelperRegistry eventRegistry = Mockito.mock(EventRagHelperRegistry.class);
        CityInfoRagHelperRegistry cityInfoRegistry = Mockito.mock(CityInfoRagHelperRegistry.class);
        TelegramConfiguration telegramConfig = mockTelegramConfig(Set.of("testcity"));

        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                "", telegramConfig);

        service.onStartup();

        verify(procedureRegistry).getForCity("testcity");
        verify(procedureRegistry, never()).getForCity("elprat");
    }

    @Test
    void onStartup_exceptionFromProcedureRegistry_isSwallowed() {
        ProcedureRagHelperRegistry procedureRegistry = Mockito.mock(ProcedureRagHelperRegistry.class);
        EventRagHelperRegistry eventRegistry = Mockito.mock(EventRagHelperRegistry.class);
        CityInfoRagHelperRegistry cityInfoRegistry = Mockito.mock(CityInfoRagHelperRegistry.class);
        TelegramConfiguration telegramConfig = mockTelegramConfig(Set.of());
        when(procedureRegistry.getForCity("elprat")).thenThrow(new RuntimeException("RAG load failed"));

        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                "elprat", telegramConfig);

        assertDoesNotThrow(() -> service.onStartup(),
                "Exception from procedureRegistry must not propagate from onStartup");
    }

    @Test
    void onStartup_noCitiesConfigured_skipsWarmup() throws Exception {
        ProcedureRagHelperRegistry procedureRegistry = Mockito.mock(ProcedureRagHelperRegistry.class);
        EventRagHelperRegistry eventRegistry = Mockito.mock(EventRagHelperRegistry.class);
        CityInfoRagHelperRegistry cityInfoRegistry = Mockito.mock(CityInfoRagHelperRegistry.class);
        TelegramConfiguration telegramConfig = mockTelegramConfig(Set.of());

        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                "", telegramConfig);

        service.onStartup();

        verify(procedureRegistry, never()).getForCity(any());
        verify(eventRegistry, never()).getForCity(any());
        verify(cityInfoRegistry, never()).getForCity(any());
    }

    @Test
    void onStartup_nullTelegramConfig_usesOnlyDefaultCity() throws Exception {
        ProcedureRagHelperRegistry procedureRegistry = Mockito.mock(ProcedureRagHelperRegistry.class);
        EventRagHelperRegistry eventRegistry = Mockito.mock(EventRagHelperRegistry.class);
        CityInfoRagHelperRegistry cityInfoRegistry = Mockito.mock(CityInfoRagHelperRegistry.class);

        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                "elprat", null);

        service.onStartup();

        verify(procedureRegistry).getForCity("elprat");
        verify(eventRegistry).getForCity("elprat");
        verify(cityInfoRegistry).getForCity("elprat");
    }
}
