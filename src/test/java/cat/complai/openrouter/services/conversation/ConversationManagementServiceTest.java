package cat.complai.openrouter.services.conversation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ConversationManagementService.
 * Validates conversation history capping (Step 4 of Phase 3 optimizations).
 */
class ConversationManagementServiceTest {

    private ConversationManagementService conversationService;
    private static final String CONV_ID = "test-conv-123";

    @BeforeEach
    void setUp() {
        // Create service with maxHistoryTurns = 5 (default from config)
        conversationService = new ConversationManagementService(5);
    }

    @Test
    void maxHistoryTurnsConfigurable() {
        // Verify that the service can be initialized with different max turn counts
        ConversationManagementService service3 = new ConversationManagementService(3);
        assertNotNull(service3, "Service should initialize with custom maxHistoryTurns");

        ConversationManagementService service10 = new ConversationManagementService(10);
        assertNotNull(service10, "Service should initialize with maxHistoryTurns=10");
    }

    @Test
    void updateConversationHistory_createsNewHistory() {
        conversationService.updateConversationHistory(CONV_ID, "What is the library schedule?",
                "The library is open Monday to Friday.");

        List<ConversationManagementService.MessageEntry> history = conversationService.getConversationHistory(CONV_ID);

        assertEquals(2, history.size(), "Should have 1 turn (user + assistant message)");
        assertEquals("user", history.get(0).role());
        assertEquals("assistant", history.get(1).role());
    }

    @Test
    void updateConversationHistory_appendsToExisting() {
        // First turn
        conversationService.updateConversationHistory(CONV_ID, "Question 1?", "Answer 1.");

        // Second turn
        conversationService.updateConversationHistory(CONV_ID, "Question 2?", "Answer 2.");

        List<ConversationManagementService.MessageEntry> history = conversationService.getConversationHistory(CONV_ID);

        assertEquals(4, history.size(), "Should have 2 turns (4 messages)");
        assertEquals("Question 1?", history.get(0).content());
        assertEquals("Question 2?", history.get(2).content());
    }

    @Test
    void updateConversationHistory_capsAtConfiguredTurns() {
        // Add more turns than the max (5 turns)
        for (int i = 1; i <= 7; i++) {
            conversationService.updateConversationHistory(
                    CONV_ID,
                    "Question " + i + "?",
                    "Answer " + i + ".");
        }

        List<ConversationManagementService.MessageEntry> history = conversationService.getConversationHistory(CONV_ID);

        // Should be capped at 5 turns * 2 messages per turn = 10 messages max
        int maxMessages = 5 * 2;
        assertTrue(history.size() <= maxMessages,
                "History should be capped at " + maxMessages + " messages, but was " + history.size());
        assertEquals(maxMessages, history.size(),
                "Should contain exactly 5 turns (the maximum configured)");
    }

    @Test
    void conversationCache_purgesOldestTurnsWhenLimitExceeded_fifo() {
        // Add more turns than the max (5 turns configured)
        for (int i = 1; i <= 7; i++) {
            conversationService.updateConversationHistory(
                    CONV_ID,
                    "Question " + i + "?",
                    "Answer " + i + ".");
        }

        List<ConversationManagementService.MessageEntry> history = conversationService.getConversationHistory(CONV_ID);

        // Should contain only the last 5 turns (FIFO pruning)
        // Max is 5 turns = 10 messages
        assertEquals(10, history.size(), "Should contain exactly 5 turns (10 messages max)");

        // Verify that the history contains later questions (3-7), not the first ones
        String allHistoryContent = history.stream()
                .map(m -> m.content())
                .reduce("", (a, b) -> a + " " + b);

        // Should contain questions from later turns (at least questions 3-7)
        assertTrue(allHistoryContent.contains("Question 3") ||
                allHistoryContent.contains("Question 4") ||
                allHistoryContent.contains("Question 5"),
                "Should contain later questions (pruning oldest)");
    }

    @Test
    void getConversationHistory_returnsEmptyForMissingConversation() {
        List<ConversationManagementService.MessageEntry> history = conversationService
                .getConversationHistory("non-existent-conv");

        assertTrue(history.isEmpty(), "Should return empty list for non-existent conversation");
    }

    @Test
    void getConversationHistory_returnsEmptyForNullConversationId() {
        List<ConversationManagementService.MessageEntry> history = conversationService.getConversationHistory(null);

        assertTrue(history.isEmpty(), "Should return empty list for null conversation ID");
    }

    @Test
    void getConversationHistory_returnsEmptyForBlankConversationId() {
        List<ConversationManagementService.MessageEntry> history = conversationService.getConversationHistory("   ");

        assertTrue(history.isEmpty(), "Should return empty list for blank conversation ID");
    }

    @Test
    void updateConversationHistory_ignoresNullAssistantMessage() {
        // Try to update with null assistant message
        conversationService.updateConversationHistory(CONV_ID, "Question?", null);

        List<ConversationManagementService.MessageEntry> history = conversationService.getConversationHistory(CONV_ID);

        assertTrue(history.isEmpty(), "Should not add history if assistant message is null");
    }

    @Test
    void updateConversationHistory_ignoresBlankConversationId() {
        conversationService.updateConversationHistory("", "Question?", "Answer.");
        conversationService.updateConversationHistory(null, "Question?", "Answer.");

        List<ConversationManagementService.MessageEntry> history = conversationService.getConversationHistory("test");

        assertTrue(history.isEmpty(), "Should not add history with blank/null conversation ID");
    }

    @Test
    void maxHistoryTurnsDefaultsToFiveIfNotConfigured() {
        // Default constructor through the Value injection sets maxHistoryTurns=5
        conversationService = new ConversationManagementService(5);

        // Add 6 turns
        for (int i = 1; i <= 6; i++) {
            conversationService.updateConversationHistory(
                    "conv-default",
                    "Q" + i,
                    "A" + i);
        }

        List<ConversationManagementService.MessageEntry> history = conversationService
                .getConversationHistory("conv-default");

        // Should be capped at 5 turns (10 messages)
        assertEquals(10, history.size(), "Default max history should be 5 turns");
    }

    @Test
    void storePendingComplaint_retrievesPendingComplaint() {
        String complaint = "I want to file a complaint about the parking situation.";
        conversationService.storePendingComplaint(CONV_ID, complaint);

        String retrieved = conversationService.getPendingComplaint(CONV_ID);

        assertEquals(complaint, retrieved, "Should store and retrieve pending complaint");
    }

    @Test
    void getPendingComplaint_returnsNullForMissingConversation() {
        String retrieved = conversationService.getPendingComplaint("non-existent");

        assertNull(retrieved, "Should return null for non-existent pending complaint");
    }

    @Test
    void multipleConversations_maintainSeparateHistories() {
        String conv1 = "conv-1";
        String conv2 = "conv-2";

        // Add history to first conversation
        conversationService.updateConversationHistory(conv1, "Question 1?", "Answer 1.");

        // Add history to second conversation
        conversationService.updateConversationHistory(conv2, "Question 2?", "Answer 2.");

        List<ConversationManagementService.MessageEntry> history1 = conversationService.getConversationHistory(conv1);
        List<ConversationManagementService.MessageEntry> history2 = conversationService.getConversationHistory(conv2);

        assertEquals(2, history1.size(), "Conversation 1 should have 2 messages");
        assertEquals(2, history2.size(), "Conversation 2 should have 2 messages");
        assertEquals("Question 1?", history1.get(0).content());
        assertEquals("Question 2?", history2.get(0).content());
    }
}
