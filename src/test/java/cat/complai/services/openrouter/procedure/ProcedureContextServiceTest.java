package cat.complai.services.openrouter.procedure;

import cat.complai.dto.openrouter.Source;
import cat.complai.helpers.openrouter.CityInfoRagHelperRegistry;
import cat.complai.helpers.openrouter.EventRagHelperRegistry;
import cat.complai.helpers.openrouter.NewsRagHelperRegistry;
import cat.complai.helpers.openrouter.ProcedureRagHelperRegistry;
import cat.complai.helpers.openrouter.RagHelper;
import cat.complai.helpers.openrouter.RedactPromptBuilder;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class ProcedureContextServiceTest {

        @Inject
        ProcedureContextService procedureContextService;

        @Test
        void buildProcedureContextResult_returnsMaxThreeMatches() {
                ProcedureContextService.ProcedureContextResult result = procedureContextService
                                .buildProcedureContextResult("recycling waste", "testcity");

                assertNotNull(result);
                assertNotNull(result.getSources());
                assertTrue(result.getSources().size() <= 3);
        }

        @Test
        void buildEventContextResult_returnsMaxThreeMatches() {
                ProcedureContextService.EventContextResult result = procedureContextService
                                .buildEventContextResult("festival concert", "testcity");

                assertNotNull(result);
                assertNotNull(result.getSources());
                assertTrue(result.getSources().size() <= 3);
        }

        @Test
        void buildProcedureContextResult_emptyQuery_returnsEmpty() {
                ProcedureContextService.ProcedureContextResult result = procedureContextService
                                .buildProcedureContextResult("", "testcity");

                assertNotNull(result);
                assertTrue(result.getSources().isEmpty());
        }

        @Test
        void buildEventContextResult_emptyQuery_returnsEmpty() {
                ProcedureContextService.EventContextResult result = procedureContextService
                                .buildEventContextResult("", "testcity");

                assertNotNull(result);
                assertTrue(result.getSources().isEmpty());
        }

        @Test
        void deDuplicateAndOrderSources_preservesFirstOccurrence() {
                List<Source> sourcesWithDuplicates = new ArrayList<>();
                sourcesWithDuplicates.add(new Source("https://example.com/proc1", "Procedure 1"));
                sourcesWithDuplicates.add(new Source("https://example.com/proc1", "Procedure 1"));
                sourcesWithDuplicates.add(new Source("https://example.com/proc2", "Procedure 2"));
                sourcesWithDuplicates.add(new Source("https://example.com/proc3", "Procedure 3"));
                sourcesWithDuplicates.add(new Source("https://example.com/proc2", "Procedure 2"));

                List<Source> deduped = procedureContextService.deDuplicateAndOrderSources(sourcesWithDuplicates);

                assertEquals(3, deduped.size());
                assertEquals("https://example.com/proc1", deduped.get(0).getUrl());
                assertEquals("https://example.com/proc2", deduped.get(1).getUrl());
                assertEquals("https://example.com/proc3", deduped.get(2).getUrl());
        }

        @Test
        void questionNeedsProcedureContext_detects_keywords() {
                assertTrue(procedureContextService.questionNeedsProcedureContext("How do I apply for a permit?",
                                "testcity"));
                assertTrue(procedureContextService.questionNeedsProcedureContext("What are the requirements?",
                                "testcity"));
                assertFalse(procedureContextService.questionNeedsProcedureContext("What is the weather today?",
                                "testcity"));
        }

        @Test
        void questionNeedsEventContext_detects_keywords() {
                assertTrue(procedureContextService.questionNeedsEventContext("What events are happening?", "testcity"));
                assertTrue(procedureContextService.questionNeedsEventContext("What's on this weekend?", "testcity"));
                assertFalse(procedureContextService.questionNeedsEventContext("Tell me a joke", "testcity"));
        }

        @Test
        void requiresEventDateWindowClarification_returnsTrue_whenEventIntentHasNoDateWindow() {
                assertTrue(procedureContextService.requiresEventDateWindowClarification(
                                "Quins esdeveniments hi ha?", "testcity"));
                assertTrue(procedureContextService.requiresEventDateWindowClarification(
                                "What events are happening?", "testcity"));
                assertTrue(procedureContextService.requiresEventDateWindowClarification(
                                "Que eventos hay en la ciudad?", "testcity"));
        }

        @Test
        void requiresEventDateWindowClarification_returnsFalse_whenDateWindowExists() {
                assertFalse(procedureContextService.requiresEventDateWindowClarification(
                                "What events are happening this week?", "testcity"));
                assertFalse(procedureContextService.requiresEventDateWindowClarification(
                                "Quins esdeveniments hi ha a l'abril?", "testcity"));
                assertFalse(procedureContextService.requiresEventDateWindowClarification(
                                "Que eventos hay del 10/04 al 15/04?", "testcity"));
        }

        @Test
        void questionNeedsNewsContext_detects_keywords() {
                assertTrue(procedureContextService.questionNeedsNewsContext("Any latest news in the city?",
                                "testcity"));
                assertTrue(procedureContextService.questionNeedsNewsContext("Que diu l'actualitat municipal?",
                                "testcity"));
                assertFalse(procedureContextService.questionNeedsNewsContext("How do I apply for a permit?",
                                "testcity"));
        }

        @Test
        void buildNewsContextResult_returnsEmpty_whenNoMatches() {
                ProcedureContextService.NewsContextResult result = procedureContextService
                                .buildNewsContextResult("martian taxation", "testcity");

                assertNotNull(result);
                assertTrue(result.getSources().isEmpty());
        }

        @Test
        void detectContextRequirements_reusesNormalizedProcedureTitlesPerCity() throws Exception {
                CountingProcedureRagHelper procedureHelper = new CountingProcedureRagHelper(
                                List.of(new RagHelper.Procedure("p1", "Resident Parking Badge Renewal", "", "",
                                                "",
                                                "https://example.com/procedure")));
                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", procedureHelper)),
                                new TestEventRegistry(Map.of("city-a", new CountingEventRagHelper(List.of()))),
                                new TestNewsRegistry(Map.of("city-a", new CountingNewsRagHelper(List.of()))),
                                new TestCityInfoRegistry(Map.of("city-a", new CountingCityInfoRagHelper(List.of()))),
                                new RedactPromptBuilder());

                assertTrue(service.questionNeedsProcedureContext("Need resident parking badge renewal today",
                                "city-a"));
                assertTrue(service.questionNeedsProcedureContext("Need resident parking badge renewal today",
                                "city-a"));
                assertEquals(1, procedureHelper.getAllProceduresCalls.get());
        }

        @Test
        void detectContextRequirements_reusesNormalizedEventTitlesPerCity() throws Exception {
                CountingEventRagHelper eventHelper = new CountingEventRagHelper(
                                List.of(new RagHelper.Event("e1", "Neighborhood Meetup", "", "", "", "", "", "",
                                                "",
                                                "https://example.com/event")));
                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", new CountingProcedureRagHelper(List.of()))),
                                new TestEventRegistry(Map.of("city-a", eventHelper)),
                                new TestNewsRegistry(Map.of("city-a", new CountingNewsRagHelper(List.of()))),
                                new TestCityInfoRegistry(Map.of("city-a", new CountingCityInfoRagHelper(List.of()))),
                                new RedactPromptBuilder());

                assertTrue(service.questionNeedsEventContext("Can I join the neighborhood meetup tonight?", "city-a"));
                assertTrue(service.questionNeedsEventContext("Can I join the neighborhood meetup tonight?", "city-a"));
                assertEquals(1, eventHelper.getAllEventsCalls.get());
        }

        @Test
        void detectContextRequirements_keywordAndConversationalShortCircuitsSkipTitleIndexLookup() throws Exception {
                CountingProcedureRagHelper procedureHelper = new CountingProcedureRagHelper(
                                List.of(new RagHelper.Procedure("p1", "Resident Parking Badge Renewal", "", "",
                                                "",
                                                "https://example.com/procedure")));
                CountingEventRagHelper eventHelper = new CountingEventRagHelper(
                                List.of(new RagHelper.Event("e1", "Moonlight Concert", "", "", "", "", "", "", "",
                                                "https://example.com/event")));
                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", procedureHelper)),
                                new TestEventRegistry(Map.of("city-a", eventHelper)),
                                new TestNewsRegistry(Map.of("city-a", new CountingNewsRagHelper(List.of()))),
                                new TestCityInfoRegistry(Map.of("city-a", new CountingCityInfoRagHelper(List.of()))),
                                new RedactPromptBuilder());

                ProcedureContextService.ContextRequirements keywordRequirements = service
                                .detectContextRequirements("How do I apply for a permit?", "city-a");
                ProcedureContextService.ContextRequirements conversationalRequirements = service
                                .detectContextRequirements("Tell me a joke", "city-a");

                assertTrue(keywordRequirements.needsProcedureContext());
                assertFalse(keywordRequirements.needsEventContext());
                assertFalse(keywordRequirements.needsNewsContext());
                assertFalse(conversationalRequirements.needsProcedureContext());
                assertFalse(conversationalRequirements.needsEventContext());
                assertFalse(conversationalRequirements.needsNewsContext());
                assertEquals(0, procedureHelper.getAllProceduresCalls.get());
                assertEquals(0, eventHelper.getAllEventsCalls.get());
        }

        @Test
        void detectContextRequirements_keepsCityScopedIsolationForDirectTitleMatches() throws Exception {
                CountingProcedureRagHelper cityAHelper = new CountingProcedureRagHelper(
                                List.of(new RagHelper.Procedure("p1", "Resident Parking Badge Renewal", "", "",
                                                "",
                                                "https://example.com/a")));
                CountingProcedureRagHelper cityBHelper = new CountingProcedureRagHelper(
                                List.of(new RagHelper.Procedure("p2", "Beach Access Permit", "", "", "",
                                                "https://example.com/b")));

                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", cityAHelper, "city-b", cityBHelper)),
                                new TestEventRegistry(Map.of(
                                                "city-a", new CountingEventRagHelper(List.of()),
                                                "city-b", new CountingEventRagHelper(List.of()))),
                                new TestNewsRegistry(Map.of(
                                                "city-a", new CountingNewsRagHelper(List.of()),
                                                "city-b", new CountingNewsRagHelper(List.of()))),
                                new TestCityInfoRegistry(Map.of(
                                                "city-a", new CountingCityInfoRagHelper(List.of()),
                                                "city-b", new CountingCityInfoRagHelper(List.of()))),
                                new RedactPromptBuilder());

                assertTrue(service.questionNeedsProcedureContext("Need resident parking badge renewal today",
                                "city-a"));
                assertFalse(service.questionNeedsProcedureContext("Need resident parking badge renewal today",
                                "city-b"));
                assertTrue(service.questionNeedsProcedureContext("Need beach access permit today", "city-b"));
        }

        @Test
        void detectContextRequirements_matchesDirectProcedureAndEventTitlesSeparately() throws Exception {
                CountingProcedureRagHelper procedureHelper = new CountingProcedureRagHelper(
                                List.of(new RagHelper.Procedure("p1", "Resident Parking Badge Renewal", "", "",
                                                "",
                                                "https://example.com/procedure")));
                CountingEventRagHelper eventHelper = new CountingEventRagHelper(
                                List.of(new RagHelper.Event("e1", "Moonlight Concert", "", "", "", "", "", "", "",
                                                "https://example.com/event")));
                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", procedureHelper)),
                                new TestEventRegistry(Map.of("city-a", eventHelper)),
                                new TestNewsRegistry(Map.of("city-a", new CountingNewsRagHelper(List.of()))),
                                new TestCityInfoRegistry(Map.of("city-a", new CountingCityInfoRagHelper(List.of()))),
                                new RedactPromptBuilder());

                ProcedureContextService.ContextRequirements procedureRequirements = service
                                .detectContextRequirements("Need resident parking badge renewal today", "city-a");
                ProcedureContextService.ContextRequirements eventRequirements = service
                                .detectContextRequirements("Can I join the moonlight concert tonight?", "city-a");

                assertTrue(procedureRequirements.needsProcedureContext());
                assertFalse(procedureRequirements.needsEventContext());
                assertFalse(procedureRequirements.needsNewsContext());
                assertFalse(eventRequirements.needsProcedureContext());
                assertTrue(eventRequirements.needsEventContext());
                assertFalse(eventRequirements.needsNewsContext());
        }

        @Test
        void detectContextRequirements_ambiguousQueryUsesTitleIndexLookup() throws Exception {
                CountingProcedureRagHelper procedureHelper = new CountingProcedureRagHelper(
                                List.of(new RagHelper.Procedure("p1", "Resident Parking Badge Renewal", "", "",
                                                "",
                                                "https://example.com/procedure")));
                CountingEventRagHelper eventHelper = new CountingEventRagHelper(
                                List.of(new RagHelper.Event("e1", "Moonlight Concert", "", "", "", "", "", "", "",
                                                "https://example.com/event")));
                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", procedureHelper)),
                                new TestEventRegistry(Map.of("city-a", eventHelper)),
                                new TestNewsRegistry(Map.of("city-a", new CountingNewsRagHelper(List.of()))),
                                new TestCityInfoRegistry(Map.of("city-a", new CountingCityInfoRagHelper(List.of()))),
                                new RedactPromptBuilder());

                ProcedureContextService.ContextRequirements requirements = service
                                .detectContextRequirements("resident parking badge renewal", "city-a");

                assertTrue(requirements.needsProcedureContext());
                assertFalse(requirements.needsEventContext());
                assertFalse(requirements.needsNewsContext());
                assertEquals(1, procedureHelper.getAllProceduresCalls.get());
                assertEquals(1, eventHelper.getAllEventsCalls.get());
        }

        @Test
        void detectContextRequirements_newsIntent_skipsProcedureAndEventTitleIndexes() throws Exception {
                CountingProcedureRagHelper procedureHelper = new CountingProcedureRagHelper(List.of(
                                new RagHelper.Procedure("p1", "Resident Parking Badge Renewal", "", "", "",
                                                "https://example.com/procedure")));
                CountingEventRagHelper eventHelper = new CountingEventRagHelper(List.of(
                                new RagHelper.Event("e1", "Moonlight Concert", "", "", "", "", "", "", "",
                                                "https://example.com/event")));
                CountingNewsRagHelper newsHelper = new CountingNewsRagHelper(List.of(
                                new RagHelper.News("n1", "Latest municipal recycling update", "", "", "", "", "",
                                                "https://example.com/news")));

                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", procedureHelper)),
                                new TestEventRegistry(Map.of("city-a", eventHelper)),
                                new TestNewsRegistry(Map.of("city-a", newsHelper)),
                                new TestCityInfoRegistry(Map.of("city-a", new CountingCityInfoRagHelper(List.of()))),
                                new RedactPromptBuilder());

                ProcedureContextService.ContextRequirements requirements = service
                                .detectContextRequirements("Any latest news in the city?", "city-a");

                assertFalse(requirements.needsProcedureContext());
                assertFalse(requirements.needsEventContext());
                assertTrue(requirements.needsNewsContext());
                assertFalse(requirements.needsCityInfoContext());
                assertEquals(0, procedureHelper.getAllProceduresCalls.get());
                assertEquals(0, eventHelper.getAllEventsCalls.get());
                assertEquals(0, newsHelper.getAllNewsCalls.get());
        }

        @Test
        void detectContextRequirements_cityInfoFallback_triggersWhenNoProcedureEventNewsMatch() throws Exception {
                CountingCityInfoRagHelper cityInfoHelper = new CountingCityInfoRagHelper(List.of(
                                new RagHelper.CityInfo("c1", "Turisme", "Punts d'interès", "", "", "",
                                                "https://example.com/cityinfo")));

                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", new CountingProcedureRagHelper(List.of()))),
                                new TestEventRegistry(Map.of("city-a", new CountingEventRagHelper(List.of()))),
                                new TestNewsRegistry(Map.of("city-a", new CountingNewsRagHelper(List.of()))),
                                new TestCityInfoRegistry(Map.of("city-a", cityInfoHelper)),
                                new RedactPromptBuilder());

                ProcedureContextService.ContextRequirements requirements = service
                                .detectContextRequirements("Punts d'interès local", "city-a");

                assertFalse(requirements.needsProcedureContext());
                assertFalse(requirements.needsEventContext());
                assertFalse(requirements.needsNewsContext());
                assertTrue(requirements.needsCityInfoContext());
                assertEquals(1, cityInfoHelper.getAllCityInfoCalls.get());
        }

        private static final class TestProcedureRegistry extends ProcedureRagHelperRegistry {
                private final Map<String, RagHelper<RagHelper.Procedure>> helpersByCity;

                private TestProcedureRegistry(Map<String, RagHelper<RagHelper.Procedure>> helpersByCity) {
                        this.helpersByCity = helpersByCity;
                }

                @Override
                public RagHelper<RagHelper.Procedure> getForCity(String cityId) {
                        return helpersByCity.get(cityId);
                }
        }

        private static final class TestEventRegistry extends EventRagHelperRegistry {
                private final Map<String, RagHelper<RagHelper.Event>> helpersByCity;

                private TestEventRegistry(Map<String, RagHelper<RagHelper.Event>> helpersByCity) {
                        this.helpersByCity = helpersByCity;
                }

                @Override
                public RagHelper<RagHelper.Event> getForCity(String cityId) {
                        return helpersByCity.get(cityId);
                }
        }

        private static final class TestNewsRegistry extends NewsRagHelperRegistry {
                private final Map<String, RagHelper<RagHelper.News>> helpersByCity;

                private TestNewsRegistry(Map<String, RagHelper<RagHelper.News>> helpersByCity) {
                        this.helpersByCity = helpersByCity;
                }

                @Override
                public RagHelper<RagHelper.News> getForCity(String cityId) {
                        return helpersByCity.get(cityId);
                }
        }

        private static final class TestCityInfoRegistry extends CityInfoRagHelperRegistry {
                private final Map<String, RagHelper<RagHelper.CityInfo>> helpersByCity;

                private TestCityInfoRegistry(Map<String, RagHelper<RagHelper.CityInfo>> helpersByCity) {
                        this.helpersByCity = helpersByCity;
                }

                @Override
                public RagHelper<RagHelper.CityInfo> getForCity(String cityId) {
                        return helpersByCity.get(cityId);
                }
        }

        private static final class CountingProcedureRagHelper extends RagHelper<RagHelper.Procedure> {
                private final List<RagHelper.Procedure> procedures;
                private final AtomicInteger getAllProceduresCalls = new AtomicInteger();

                private CountingProcedureRagHelper(List<RagHelper.Procedure> procedures) {
                        super("testcity", RagHelper.procedureDomainConfig());
                        this.procedures = procedures;
                }

                @Override
                public List<RagHelper.Procedure> getAll() {
                        getAllProceduresCalls.incrementAndGet();
                        return procedures;
                }

                @Override
                public List<RagHelper.Procedure> search(String query) {
                        return procedures;
                }
        }

        private static final class CountingEventRagHelper extends RagHelper<RagHelper.Event> {
                private final List<RagHelper.Event> events;
                private final AtomicInteger getAllEventsCalls = new AtomicInteger();

                private CountingEventRagHelper(List<RagHelper.Event> events) {
                        super("testcity", RagHelper.eventDomainConfig());
                        this.events = events;
                }

                @Override
                public List<RagHelper.Event> getAll() {
                        getAllEventsCalls.incrementAndGet();
                        return events;
                }

                @Override
                public List<RagHelper.Event> search(String query) {
                        return events;
                }
        }

        private static final class CountingNewsRagHelper extends RagHelper<RagHelper.News> {
                private final List<RagHelper.News> news;
                private final AtomicInteger getAllNewsCalls = new AtomicInteger();

                private CountingNewsRagHelper(List<RagHelper.News> news) {
                        super("testcity", RagHelper.newsDomainConfig());
                        this.news = news;
                }

                @Override
                public List<RagHelper.News> getAll() {
                        getAllNewsCalls.incrementAndGet();
                        return news;
                }

                @Override
                public List<RagHelper.News> search(String query) {
                        return news;
                }
        }

        private static final class CountingCityInfoRagHelper extends RagHelper<RagHelper.CityInfo> {
                private final List<RagHelper.CityInfo> cityInfo;
                private final AtomicInteger getAllCityInfoCalls = new AtomicInteger();

                private CountingCityInfoRagHelper(List<RagHelper.CityInfo> cityInfo) {
                        super("testcity", RagHelper.cityInfoDomainConfig());
                        this.cityInfo = cityInfo;
                }

                @Override
                public List<RagHelper.CityInfo> getAll() {
                        getAllCityInfoCalls.incrementAndGet();
                        return cityInfo;
                }

                @Override
                public List<RagHelper.CityInfo> search(String query) {
                        return cityInfo;
                }
        }

}
