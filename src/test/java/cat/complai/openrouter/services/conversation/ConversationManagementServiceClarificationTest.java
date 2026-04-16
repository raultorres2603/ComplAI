package cat.complai.openrouter.services.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConversationManagementServiceClarificationTest {

    private ConversationManagementService service;
    private static final String CONV_ID = "clarification-conv-456";

    @BeforeEach
    void setUp() {
        service = new ConversationManagementService(5);
    }

    @Test
    void storeThenGet_returnsCandidates() {
        List<ConversationManagementService.ClarificationCandidate> candidates = List.of(
                new ConversationManagementService.ClarificationCandidate("proc-1", "Renew passport"),
                new ConversationManagementService.ClarificationCandidate("proc-2", "Renew ID card")
        );

        service.storePendingClarification(CONV_ID, candidates);
        List<ConversationManagementService.ClarificationCandidate> result = service.getPendingClarification(CONV_ID);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("proc-1", result.get(0).procedureId());
        assertEquals("Renew passport", result.get(0).title());
        assertEquals("proc-2", result.get(1).procedureId());
    }

    @Test
    void getPendingClarification_nullConversationId_returnsNull() {
        assertNull(service.getPendingClarification(null));
        assertNull(service.getPendingClarification(""));
        assertNull(service.getPendingClarification("   "));
    }

    @Test
    void clearPendingClarification_removesEntry() {
        List<ConversationManagementService.ClarificationCandidate> candidates = List.of(
                new ConversationManagementService.ClarificationCandidate("proc-1", "Renew passport")
        );
        service.storePendingClarification(CONV_ID, candidates);
        assertNotNull(service.getPendingClarification(CONV_ID));

        service.clearPendingClarification(CONV_ID);

        assertNull(service.getPendingClarification(CONV_ID));
    }

    @Test
    void storePendingClarification_nullConversationId_isIgnored() {
        List<ConversationManagementService.ClarificationCandidate> candidates = List.of(
                new ConversationManagementService.ClarificationCandidate("proc-1", "Renew passport")
        );

        service.storePendingClarification(null, candidates);
        service.storePendingClarification("", candidates);
        service.storePendingClarification("   ", candidates);

        assertNull(service.getPendingClarification(null));
    }
}
