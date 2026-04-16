package cat.complai.openrouter.services.procedure;

import cat.complai.openrouter.helpers.CityInfoRagHelperRegistry;
import cat.complai.openrouter.helpers.EventRagHelperRegistry;
import cat.complai.openrouter.helpers.NewsRagHelperRegistry;
import cat.complai.openrouter.helpers.ProcedureRagHelper;
import cat.complai.openrouter.helpers.ProcedureRagHelperRegistry;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.openrouter.helpers.rag.InMemoryLexicalIndex;
import cat.complai.openrouter.helpers.rag.SearchResult;
import cat.complai.openrouter.services.conversation.ConversationManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ProcedureContextServiceAmbiguityTest {

    @Mock
    ProcedureRagHelperRegistry ragRegistry;
    @Mock
    EventRagHelperRegistry eventRagRegistry;
    @Mock
    NewsRagHelperRegistry newsRagRegistry;
    @Mock
    CityInfoRagHelperRegistry cityInfoRagRegistry;
    @Mock
    RedactPromptBuilder promptBuilder;
    @Mock
    ProcedureRagHelper procedureRagHelper;

    ProcedureContextService service;

    private static final String CITY = "testcity";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ProcedureContextService(ragRegistry, eventRagRegistry, newsRagRegistry,
                cityInfoRagRegistry, promptBuilder);
    }

    @Test
    void detectProcedureAmbiguity_returnsEmpty_whenQueryRequiresNoProcedureContext() {
        Optional<ProcedureContextService.ProcedureAmbiguityResult> result =
                service.detectProcedureAmbiguity("hello", CITY);

        assertTrue(result.isEmpty());
    }

    @Test
    void detectProcedureAmbiguity_returnsEmpty_whenSearchReturnsOneResult() {
        ProcedureRagHelper.Procedure proc = new ProcedureRagHelper.Procedure(
                "proc-1", "Llicència d'obres", "Description", "Reqs", "Steps",
                "https://example.com/proc-1");

        InMemoryLexicalIndex.SearchResponse<ProcedureRagHelper.Procedure> response =
                new InMemoryLexicalIndex.SearchResponse<>(
                        List.of(new SearchResult<>(proc, 0.9, 0)),
                        0, 1, 0.9, 0.15, 0.45, 0.15);

        when(ragRegistry.getForCity(CITY)).thenReturn(procedureRagHelper);
        when(procedureRagHelper.searchWithScores("apply for permit")).thenReturn(response);

        Optional<ProcedureContextService.ProcedureAmbiguityResult> result =
                service.detectProcedureAmbiguity("apply for permit", CITY);

        assertTrue(result.isEmpty());
    }

    @Test
    void detectProcedureAmbiguity_returnsCandidates_whenScoresNearlyEqual() {
        ProcedureRagHelper.Procedure proc1 = new ProcedureRagHelper.Procedure(
                "proc-1", "Llicència d'obres menors", "Description1", "Reqs1", "Steps1",
                "https://example.com/proc-1");
        ProcedureRagHelper.Procedure proc2 = new ProcedureRagHelper.Procedure(
                "proc-2", "Llicència d'obres majors", "Description2", "Reqs2", "Steps2",
                "https://example.com/proc-2");

        // Nearly equal scores — ratio 0.92/0.95 ≈ 0.969 > default threshold 0.85
        InMemoryLexicalIndex.SearchResponse<ProcedureRagHelper.Procedure> response =
                new InMemoryLexicalIndex.SearchResponse<>(
                        List.of(
                                new SearchResult<>(proc1, 0.95, 0),
                                new SearchResult<>(proc2, 0.92, 1)),
                        0, 2, 0.95, 0.15, 0.45, 0.15);

        when(ragRegistry.getForCity(CITY)).thenReturn(procedureRagHelper);
        when(procedureRagHelper.searchWithScores("apply for permit")).thenReturn(response);

        Optional<ProcedureContextService.ProcedureAmbiguityResult> result =
                service.detectProcedureAmbiguity("apply for permit", CITY);

        assertTrue(result.isPresent());
        List<ConversationManagementService.ClarificationCandidate> candidates = result.get().candidates();
        assertEquals(2, candidates.size());
        assertEquals("proc-1", candidates.get(0).procedureId());
        assertEquals("Llicència d'obres menors", candidates.get(0).title());
        assertEquals("proc-2", candidates.get(1).procedureId());
        assertEquals("Llicència d'obres majors", candidates.get(1).title());
    }

    @Test
    void buildProcedureContextResultForId_returnsContext_whenFound() {
        ProcedureRagHelper.Procedure proc = new ProcedureRagHelper.Procedure(
                "proc-1", "Llicència d'obres menors", "Description", "Reqs", "Steps",
                "https://example.com/proc-1");

        when(ragRegistry.getForCity(CITY)).thenReturn(procedureRagHelper);
        when(procedureRagHelper.getAllProcedures()).thenReturn(List.of(proc));
        when(promptBuilder.buildProcedureContextBlockFromMatches(List.of(proc), CITY))
                .thenReturn("CONTEXT BLOCK");

        ProcedureContextService.ProcedureContextResult result =
                service.buildProcedureContextResultForId("proc-1", CITY);

        assertNotNull(result);
        assertEquals("CONTEXT BLOCK", result.getContextBlock());
        assertEquals(1, result.getSources().size());
        assertEquals("https://example.com/proc-1", result.getSources().get(0).getUrl());
        assertEquals("Llicència d'obres menors", result.getSources().get(0).getTitle());
    }

    @Test
    void buildProcedureContextResultForId_returnsEmpty_whenNotFound() {
        when(ragRegistry.getForCity(CITY)).thenReturn(procedureRagHelper);
        when(procedureRagHelper.getAllProcedures()).thenReturn(List.of());

        ProcedureContextService.ProcedureContextResult result =
                service.buildProcedureContextResultForId("proc-unknown", CITY);

        assertNotNull(result);
        assertNull(result.getContextBlock());
        assertTrue(result.getSources().isEmpty());
    }
}
