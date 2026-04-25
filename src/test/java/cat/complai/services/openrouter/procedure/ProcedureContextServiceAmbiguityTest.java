package cat.complai.services.openrouter.procedure;

import cat.complai.helpers.openrouter.CityInfoRagHelperRegistry;
import cat.complai.helpers.openrouter.EventRagHelperRegistry;
import cat.complai.helpers.openrouter.NewsRagHelperRegistry;
import cat.complai.helpers.openrouter.RagHelper;
import cat.complai.helpers.openrouter.ProcedureRagHelperRegistry;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.helpers.openrouter.rag.InMemoryLexicalIndex;
import cat.complai.helpers.openrouter.rag.SearchResult;
import cat.complai.services.openrouter.conversation.ConversationManagementService;
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
    @SuppressWarnings("unchecked")
    RagHelper procedureRagHelper;

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
        RagHelper.Procedure proc = new RagHelper.Procedure(
                "proc-1", "Llicència d'obres", "Description", "Reqs", "Steps",
                "https://example.com/proc-1");

        InMemoryLexicalIndex.SearchResponse<RagHelper.Procedure> response =
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
        RagHelper.Procedure proc1 = new RagHelper.Procedure(
                "proc-1", "Llicència d'obres menors", "permit request", "Reqs1", "Steps1",
                "https://example.com/proc-1");
        RagHelper.Procedure proc2 = new RagHelper.Procedure(
                "proc-2", "Llicència d'obres majors", "permit application", "Reqs2", "Steps2",
                "https://example.com/proc-2");

        // Nearly equal scores — ratio 0.92/0.95 ≈ 0.969 > default threshold 0.85
        InMemoryLexicalIndex.SearchResponse<RagHelper.Procedure> response =
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
    void detectProcedureAmbiguity_filtersUnrelatedHousing_whenParkingCandidatesExist() {
        RagHelper.Procedure parkingA = new RagHelper.Procedure(
                "proc-parking-a", "Tarjeta de aparcamiento municipal", "Solicitud de aparcamiento", "", "",
                "https://example.com/parking-a");
        RagHelper.Procedure housing = new RagHelper.Procedure(
                "proc-housing", "Ayudas de vivienda social", "Subvenciones de alquiler", "", "",
                "https://example.com/housing");
        RagHelper.Procedure parkingB = new RagHelper.Procedure(
                "proc-parking-b", "Aparcamiento en avenida Verge de Montserrat", "Permiso para estacionar", "", "",
                "https://example.com/parking-b");

        InMemoryLexicalIndex.SearchResponse<RagHelper.Procedure> response =
                new InMemoryLexicalIndex.SearchResponse<>(
                        List.of(
                                new SearchResult<>(parkingA, 0.96, 0),
                                new SearchResult<>(housing, 0.95, 1),
                                new SearchResult<>(parkingB, 0.94, 2)),
                        0, 3, 0.96, 0.15, 0.45, 0.15);

        when(ragRegistry.getForCity(CITY)).thenReturn(procedureRagHelper);
        when(procedureRagHelper.searchWithScores("tramite aparcamiento municipal avenida verge montserrat"))
                .thenReturn(response);

        Optional<ProcedureContextService.ProcedureAmbiguityResult> result =
                service.detectProcedureAmbiguity("tramite aparcamiento municipal avenida verge montserrat", CITY);

        assertTrue(result.isPresent());
        List<ConversationManagementService.ClarificationCandidate> candidates = result.get().candidates();
        assertEquals(2, candidates.size());
        assertEquals("proc-parking-a", candidates.get(0).procedureId());
        assertEquals("proc-parking-b", candidates.get(1).procedureId());
    }

    @Test
    void detectProcedureAmbiguity_returnsEmpty_whenRelevanceFilteringLeavesSingleCandidate() {
        RagHelper.Procedure parking = new RagHelper.Procedure(
                "proc-parking", "Tarjeta de aparcamiento municipal", "Solicitud de aparcamiento", "", "",
                "https://example.com/parking");
        RagHelper.Procedure housing = new RagHelper.Procedure(
                "proc-housing", "Ayudas de vivienda social", "Subvenciones de alquiler", "", "",
                "https://example.com/housing");

        InMemoryLexicalIndex.SearchResponse<RagHelper.Procedure> response =
                new InMemoryLexicalIndex.SearchResponse<>(
                        List.of(
                                new SearchResult<>(parking, 0.95, 0),
                                new SearchResult<>(housing, 0.94, 1)),
                        0, 2, 0.95, 0.15, 0.45, 0.15);

        when(ragRegistry.getForCity(CITY)).thenReturn(procedureRagHelper);
        when(procedureRagHelper.searchWithScores("tramite aparcamiento municipal"))
                .thenReturn(response);

        Optional<ProcedureContextService.ProcedureAmbiguityResult> result =
                service.detectProcedureAmbiguity("tramite aparcamiento municipal", CITY);

        assertTrue(result.isEmpty());
    }

    @Test
    void buildProcedureContextResultForId_returnsContext_whenFound() {
        RagHelper.Procedure proc = new RagHelper.Procedure(
                "proc-1", "Llicència d'obres menors", "Description", "Reqs", "Steps",
                "https://example.com/proc-1");

        when(ragRegistry.getForCity(CITY)).thenReturn(procedureRagHelper);
        when(procedureRagHelper.getAll()).thenReturn(List.of(proc));
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
        when(procedureRagHelper.getAll()).thenReturn(List.of());

        ProcedureContextService.ProcedureContextResult result =
                service.buildProcedureContextResultForId("proc-unknown", CITY);

        assertNotNull(result);
        assertNull(result.getContextBlock());
        assertTrue(result.getSources().isEmpty());
    }
}
