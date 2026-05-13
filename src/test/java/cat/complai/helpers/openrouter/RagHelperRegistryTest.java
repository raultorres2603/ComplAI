package cat.complai.helpers.openrouter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RagHelperRegistryTest {

    @Test
    void eventRegistry_createsHelperForKnownCity() {
        EventRagHelperRegistry registry = new EventRagHelperRegistry();
        RagHelper<RagHelper.Event> helper = registry.getForCity("testcity");
        assertNotNull(helper);
    }

    @Test
    void eventRegistry_cachesHelperForSameCity() {
        EventRagHelperRegistry registry = new EventRagHelperRegistry();
        RagHelper<RagHelper.Event> first = registry.getForCity("testcity");
        RagHelper<RagHelper.Event> second = registry.getForCity("testcity");
        assertSame(first, second);
    }

    @Test
    void eventRegistry_differentCitiesReturnDifferentInstances() {
        EventRagHelperRegistry registry = new EventRagHelperRegistry();
        RagHelper<RagHelper.Event> forTestcity = registry.getForCity("testcity");
        RagHelper<RagHelper.Event> forElprat = registry.getForCity("elprat");
        assertNotNull(forTestcity);
        assertNotNull(forElprat);
        assertNotSame(forTestcity, forElprat);
    }

    @Test
    void newsRegistry_createsHelperForKnownCity() {
        NewsRagHelperRegistry registry = new NewsRagHelperRegistry();
        RagHelper<RagHelper.News> helper = registry.getForCity("testcity");
        assertNotNull(helper);
    }

    @Test
    void newsRegistry_cachesHelperForSameCity() {
        NewsRagHelperRegistry registry = new NewsRagHelperRegistry();
        RagHelper<RagHelper.News> first = registry.getForCity("testcity");
        RagHelper<RagHelper.News> second = registry.getForCity("testcity");
        assertSame(first, second);
    }

    @Test
    void newsRegistry_differentCitiesReturnDifferentInstances() {
        NewsRagHelperRegistry registry = new NewsRagHelperRegistry();
        RagHelper<RagHelper.News> forTestcity = registry.getForCity("testcity");
        RagHelper<RagHelper.News> forElprat = registry.getForCity("elprat");
        assertNotNull(forTestcity);
        assertNotNull(forElprat);
        assertNotSame(forTestcity, forElprat);
    }

    @Test
    void procedureRegistry_createsHelperForKnownCity() {
        ProcedureRagHelperRegistry registry = new ProcedureRagHelperRegistry();
        RagHelper<RagHelper.Procedure> helper = registry.getForCity("testcity");
        assertNotNull(helper);
    }

    @Test
    void procedureRegistry_cachesHelperForSameCity() {
        ProcedureRagHelperRegistry registry = new ProcedureRagHelperRegistry();
        RagHelper<RagHelper.Procedure> first = registry.getForCity("testcity");
        RagHelper<RagHelper.Procedure> second = registry.getForCity("testcity");
        assertSame(first, second);
    }

    @Test
    void procedureRegistry_differentCitiesReturnDifferentInstances() {
        ProcedureRagHelperRegistry registry = new ProcedureRagHelperRegistry();
        RagHelper<RagHelper.Procedure> forTestcity = registry.getForCity("testcity");
        RagHelper<RagHelper.Procedure> forElprat = registry.getForCity("elprat");
        assertNotNull(forTestcity);
        assertNotNull(forElprat);
        assertNotSame(forTestcity, forElprat);
    }

    @Test
    void cityInfoRegistry_createsHelperForKnownCity() {
        CityInfoRagHelperRegistry registry = new CityInfoRagHelperRegistry();
        RagHelper<RagHelper.CityInfo> helper = registry.getForCity("testcity");
        assertNotNull(helper);
    }

    @Test
    void cityInfoRegistry_cachesHelperForSameCity() {
        CityInfoRagHelperRegistry registry = new CityInfoRagHelperRegistry();
        RagHelper<RagHelper.CityInfo> first = registry.getForCity("testcity");
        RagHelper<RagHelper.CityInfo> second = registry.getForCity("testcity");
        assertSame(first, second);
    }

    @Test
    void cityInfoRegistry_differentCitiesReturnDifferentInstances() {
        CityInfoRagHelperRegistry registry = new CityInfoRagHelperRegistry();
        RagHelper<RagHelper.CityInfo> forTestcity = registry.getForCity("testcity");
        RagHelper<RagHelper.CityInfo> forElprat = registry.getForCity("elprat");
        assertNotNull(forTestcity);
        assertNotNull(forElprat);
        assertNotSame(forTestcity, forElprat);
    }

    @Test
    void gencatProcedureRegistry_createsHelper() {
        GencatProcedureRagHelperRegistry registry = new GencatProcedureRagHelperRegistry();
        RagHelper<RagHelper.Procedure> helper = registry.getForCity("testcity");
        assertNotNull(helper);
    }

    @Test
    void gencatProcedureRegistry_alwaysLoadsGencatData() {
        GencatProcedureRagHelperRegistry registry = new GencatProcedureRagHelperRegistry();
        RagHelper<RagHelper.Procedure> withTestcity = registry.getForCity("testcity");
        RagHelper<RagHelper.Procedure> withElprat = registry.getForCity("elprat");
        assertNotNull(withTestcity);
        assertNotNull(withElprat);
        assertNotSame(withTestcity, withElprat);
    }

    @Test
    void unknownCity_returnsHelperWithEmptyDataset() {
        EventRagHelperRegistry registry = new EventRagHelperRegistry();
        RagHelper<RagHelper.Event> helper = registry.getForCity("nonexistent-city");
        assertNotNull(helper);
        assertTrue(helper.getAll().isEmpty());
    }
}
