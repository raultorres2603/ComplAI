package cat.complai.openrouter.helpers;

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
        TransparencyRagHelperRegistry transparencyRegistry = Mockito.mock(TransparencyRagHelperRegistry.class);
        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                transparencyRegistry, "elprat");

        service.onStartup();

        verify(procedureRegistry, times(1)).getForCity("elprat");
        verify(eventRegistry, times(1)).getForCity("elprat");
        verify(cityInfoRegistry, times(1)).getForCity("elprat");
        verify(transparencyRegistry, times(1)).getForCity("elprat");
    }

    @Test
    void onStartup_exceptionFromProcedureRegistry_isSwallowed() {
        ProcedureRagHelperRegistry procedureRegistry = Mockito.mock(ProcedureRagHelperRegistry.class);
        EventRagHelperRegistry eventRegistry = Mockito.mock(EventRagHelperRegistry.class);
        CityInfoRagHelperRegistry cityInfoRegistry = Mockito.mock(CityInfoRagHelperRegistry.class);
        TransparencyRagHelperRegistry transparencyRegistry = Mockito.mock(TransparencyRagHelperRegistry.class);
        when(procedureRegistry.getForCity("elprat")).thenThrow(new RuntimeException("RAG load failed"));

        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                transparencyRegistry, "elprat");

        assertDoesNotThrow(() -> service.onStartup(),
                "Exception from procedureRegistry must not propagate from onStartup");
    }

    @Test
    void onStartup_blankCityId_skipsBothCalls() throws Exception {
        ProcedureRagHelperRegistry procedureRegistry = Mockito.mock(ProcedureRagHelperRegistry.class);
        EventRagHelperRegistry eventRegistry = Mockito.mock(EventRagHelperRegistry.class);
        CityInfoRagHelperRegistry cityInfoRegistry = Mockito.mock(CityInfoRagHelperRegistry.class);
        TransparencyRagHelperRegistry transparencyRegistry = Mockito.mock(TransparencyRagHelperRegistry.class);
        RagWarmupService service = new RagWarmupService(procedureRegistry, eventRegistry, cityInfoRegistry,
                transparencyRegistry, "   ");

        service.onStartup();

        verify(procedureRegistry, never()).getForCity(any());
        verify(eventRegistry, never()).getForCity(any());
        verify(cityInfoRegistry, never()).getForCity(any());
        verify(transparencyRegistry, never()).getForCity(any());
    }
}
