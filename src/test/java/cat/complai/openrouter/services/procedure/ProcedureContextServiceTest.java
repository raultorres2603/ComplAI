package cat.complai.openrouter.services.procedure;

import cat.complai.openrouter.dto.Source;
import cat.complai.openrouter.helpers.EventRagHelper;
import cat.complai.openrouter.helpers.EventRagHelperRegistry;
import cat.complai.openrouter.helpers.ProcedureRagHelper;
import cat.complai.openrouter.helpers.ProcedureRagHelperRegistry;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
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
        void detectContextRequirements_reusesNormalizedProcedureTitlesPerCity() throws Exception {
                CountingProcedureRagHelper procedureHelper = new CountingProcedureRagHelper(
                                List.of(new ProcedureRagHelper.Procedure("p1", "Resident Parking Badge Renewal", "", "",
                                                "",
                                                "https://example.com/procedure")));
                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", procedureHelper)),
                                new TestEventRegistry(Map.of("city-a", new CountingEventRagHelper(List.of()))),
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
                                List.of(new EventRagHelper.Event("e1", "Neighborhood Meetup", "", "", "", "", "", "",
                                                "",
                                                "https://example.com/event")));
                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", new CountingProcedureRagHelper(List.of()))),
                                new TestEventRegistry(Map.of("city-a", eventHelper)),
                                new RedactPromptBuilder());

                assertTrue(service.questionNeedsEventContext("Can I join the neighborhood meetup tonight?", "city-a"));
                assertTrue(service.questionNeedsEventContext("Can I join the neighborhood meetup tonight?", "city-a"));
                assertEquals(1, eventHelper.getAllEventsCalls.get());
        }

        @Test
        void detectContextRequirements_keywordAndConversationalShortCircuitsSkipTitleIndexLookup() throws Exception {
                CountingProcedureRagHelper procedureHelper = new CountingProcedureRagHelper(
                                List.of(new ProcedureRagHelper.Procedure("p1", "Resident Parking Badge Renewal", "", "",
                                                "",
                                                "https://example.com/procedure")));
                CountingEventRagHelper eventHelper = new CountingEventRagHelper(
                                List.of(new EventRagHelper.Event("e1", "Moonlight Concert", "", "", "", "", "", "", "",
                                                "https://example.com/event")));
                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", procedureHelper)),
                                new TestEventRegistry(Map.of("city-a", eventHelper)),
                                new RedactPromptBuilder());

                ProcedureContextService.ContextRequirements keywordRequirements = service
                                .detectContextRequirements("How do I apply for a permit?", "city-a");
                ProcedureContextService.ContextRequirements conversationalRequirements = service
                                .detectContextRequirements("Tell me a joke", "city-a");

                assertTrue(keywordRequirements.needsProcedureContext());
                assertFalse(keywordRequirements.needsEventContext());
                assertFalse(conversationalRequirements.needsProcedureContext());
                assertFalse(conversationalRequirements.needsEventContext());
                assertEquals(0, procedureHelper.getAllProceduresCalls.get());
                assertEquals(0, eventHelper.getAllEventsCalls.get());
        }

        @Test
        void detectContextRequirements_keepsCityScopedIsolationForDirectTitleMatches() throws Exception {
                CountingProcedureRagHelper cityAHelper = new CountingProcedureRagHelper(
                                List.of(new ProcedureRagHelper.Procedure("p1", "Resident Parking Badge Renewal", "", "",
                                                "",
                                                "https://example.com/a")));
                CountingProcedureRagHelper cityBHelper = new CountingProcedureRagHelper(
                                List.of(new ProcedureRagHelper.Procedure("p2", "Beach Access Permit", "", "", "",
                                                "https://example.com/b")));

                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", cityAHelper, "city-b", cityBHelper)),
                                new TestEventRegistry(Map.of(
                                                "city-a", new CountingEventRagHelper(List.of()),
                                                "city-b", new CountingEventRagHelper(List.of()))),
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
                                List.of(new ProcedureRagHelper.Procedure("p1", "Resident Parking Badge Renewal", "", "",
                                                "",
                                                "https://example.com/procedure")));
                CountingEventRagHelper eventHelper = new CountingEventRagHelper(
                                List.of(new EventRagHelper.Event("e1", "Moonlight Concert", "", "", "", "", "", "", "",
                                                "https://example.com/event")));
                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", procedureHelper)),
                                new TestEventRegistry(Map.of("city-a", eventHelper)),
                                new RedactPromptBuilder());

                ProcedureContextService.ContextRequirements procedureRequirements = service
                                .detectContextRequirements("Need resident parking badge renewal today", "city-a");
                ProcedureContextService.ContextRequirements eventRequirements = service
                                .detectContextRequirements("Can I join the moonlight concert tonight?", "city-a");

                assertTrue(procedureRequirements.needsProcedureContext());
                assertFalse(procedureRequirements.needsEventContext());
                assertFalse(eventRequirements.needsProcedureContext());
                assertTrue(eventRequirements.needsEventContext());
        }

        @Test
        void detectContextRequirements_ambiguousQueryUsesTitleIndexLookup() throws Exception {
                CountingProcedureRagHelper procedureHelper = new CountingProcedureRagHelper(
                                List.of(new ProcedureRagHelper.Procedure("p1", "Resident Parking Badge Renewal", "", "",
                                                "",
                                                "https://example.com/procedure")));
                CountingEventRagHelper eventHelper = new CountingEventRagHelper(
                                List.of(new EventRagHelper.Event("e1", "Moonlight Concert", "", "", "", "", "", "", "",
                                                "https://example.com/event")));
                ProcedureContextService service = new ProcedureContextService(
                                new TestProcedureRegistry(Map.of("city-a", procedureHelper)),
                                new TestEventRegistry(Map.of("city-a", eventHelper)),
                                new RedactPromptBuilder());

                ProcedureContextService.ContextRequirements requirements = service
                                .detectContextRequirements("resident parking badge renewal", "city-a");

                assertTrue(requirements.needsProcedureContext());
                assertFalse(requirements.needsEventContext());
                assertEquals(1, procedureHelper.getAllProceduresCalls.get());
                assertEquals(1, eventHelper.getAllEventsCalls.get());
        }

        private static final class TestProcedureRegistry extends ProcedureRagHelperRegistry {
                private final Map<String, ProcedureRagHelper> helpersByCity;

                private TestProcedureRegistry(Map<String, ProcedureRagHelper> helpersByCity) {
                        this.helpersByCity = helpersByCity;
                }

                @Override
                public ProcedureRagHelper getForCity(String cityId) {
                        return helpersByCity.get(cityId);
                }
        }

        private static final class TestEventRegistry extends EventRagHelperRegistry {
                private final Map<String, EventRagHelper> helpersByCity;

                private TestEventRegistry(Map<String, EventRagHelper> helpersByCity) {
                        this.helpersByCity = helpersByCity;
                }

                @Override
                public EventRagHelper getForCity(String cityId) {
                        return helpersByCity.get(cityId);
                }
        }

        private static final class CountingProcedureRagHelper extends ProcedureRagHelper {
                private final List<ProcedureRagHelper.Procedure> procedures;
                private final AtomicInteger getAllProceduresCalls = new AtomicInteger();

                private CountingProcedureRagHelper(List<ProcedureRagHelper.Procedure> procedures) throws IOException {
                        super("testcity");
                        this.procedures = procedures;
                }

                @Override
                public List<ProcedureRagHelper.Procedure> getAllProcedures() {
                        getAllProceduresCalls.incrementAndGet();
                        return procedures;
                }

                @Override
                public List<ProcedureRagHelper.Procedure> search(String query) {
                        return procedures;
                }
        }

        private static final class CountingEventRagHelper extends EventRagHelper {
                private final List<EventRagHelper.Event> events;
                private final AtomicInteger getAllEventsCalls = new AtomicInteger();

                private CountingEventRagHelper(List<EventRagHelper.Event> events) throws IOException {
                        super("testcity");
                        this.events = events;
                }

                @Override
                public List<EventRagHelper.Event> getAllEvents() {
                        getAllEventsCalls.incrementAndGet();
                        return events;
                }

                @Override
                public List<EventRagHelper.Event> search(String query) {
                        return events;
                }
        }
}
