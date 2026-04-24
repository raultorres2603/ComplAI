package cat.complai.helpers.openrouter;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

class RagWarmupServiceTest {

    @Test
    void onStartup_warmsUpBothRegistries() throws Exception {
        ProcedureRagHelperRegistry procedureRegistry = Mockito.mock(ProcedureRagHelperRegistry.class);
        EventRagHelperRegistry eventRegistry = Mockito.mock(EventRagHelperRegistry.class);
        CityInfoRagHelperRegistry cityInfoRegistry = Mockito.mock(CityInfoRagHelperRegistry.class);
        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                "elprat");

        service.onStartup();

        verify(procedureRegistry, times(1)).getForCity("elprat");
        verify(eventRegistry, times(1)).getForCity("elprat");
        verify(cityInfoRegistry, times(1)).getForCity("elprat");
    }

    @Test
    void onStartup_exceptionFromProcedureRegistry_isSwallowed() {
        ProcedureRagHelperRegistry procedureRegistry = Mockito.mock(ProcedureRagHelperRegistry.class);
        EventRagHelperRegistry eventRegistry = Mockito.mock(EventRagHelperRegistry.class);
        CityInfoRagHelperRegistry cityInfoRegistry = Mockito.mock(CityInfoRagHelperRegistry.class);
        when(procedureRegistry.getForCity("elprat")).thenThrow(new RuntimeException("RAG load failed"));

        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                "elprat");

        assertDoesNotThrow(() -> service.onStartup(),
                "Exception from procedureRegistry must not propagate from onStartup");
    }

    @Test
    void onStartup_blankCityId_skipsBothCalls() throws Exception {
        ProcedureRagHelperRegistry procedureRegistry = Mockito.mock(ProcedureRagHelperRegistry.class);
        EventRagHelperRegistry eventRegistry = Mockito.mock(EventRagHelperRegistry.class);
        CityInfoRagHelperRegistry cityInfoRegistry = Mockito.mock(CityInfoRagHelperRegistry.class);
        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                "   ");

        service.onStartup();

        verify(procedureRegistry, never()).getForCity(any());
        verify(eventRegistry, never()).getForCity(any());
        verify(cityInfoRegistry, never()).getForCity(any());
    }
}
