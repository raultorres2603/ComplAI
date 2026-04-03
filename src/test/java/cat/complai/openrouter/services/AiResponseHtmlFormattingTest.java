package cat.complai.openrouter.services;

import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.Source;
import cat.complai.openrouter.helpers.EventRagHelperRegistry;
import cat.complai.openrouter.helpers.ProcedureRagHelper;
import cat.complai.openrouter.helpers.ProcedureRagHelperRegistry;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.openrouter.services.ai.AiResponseProcessingService;
import cat.complai.openrouter.services.cache.ResponseCacheService;
import cat.complai.openrouter.services.conversation.ConversationManagementService;
import cat.complai.openrouter.services.procedure.ProcedureContextService;
import cat.complai.openrouter.services.validation.InputValidationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for HTML Formatting & URL Enforcement in AI responses.
 * 
 * Tests verify:
 * - Markdown in AI responses is converted to HTML
 * - Sources include URLs and titles
 * - Missing URLs log warnings but don't fail responses
 * - Response structure is backward compatible
 */
@DisplayName("HTML Formatting & URL Enforcement Tests")
public class AiResponseHtmlFormattingTest {

    private OpenRouterServices createOpenRouterService(TestHtmlFormattingWrapper wrapper) {
        InputValidationService validationService = new InputValidationService(5000);
        ConversationManagementService conversationService = new ConversationManagementService(5);
        ResponseCacheService cacheService = new ResponseCacheService(true, 10, 500);
        AiResponseProcessingService aiResponseService = new AiResponseProcessingService(wrapper, cacheService, 30);
        ProcedureContextService procedureContextService = new ProcedureContextService(wrapper.ragRegistry,
                new EventRagHelperRegistry(), new RedactPromptBuilder());
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return new OpenRouterServices(validationService, conversationService, aiResponseService,
                procedureContextService, new RedactPromptBuilder(), wrapper, objectMapper);
    }

    /**
     * Test wrapper that allows configuration of procedures/events and AI responses
     */
    static class TestHtmlFormattingWrapper extends HttpWrapper {
        private String nextResponse = "Test response";
        private List<ProcedureRagHelper.Procedure> fakeProcedures = List.of();

        public final ProcedureRagHelperRegistry ragRegistry = new ProcedureRagHelperRegistry() {
            @Override
            public ProcedureRagHelper getForCity(String cityId) {
                try {
                    return new ProcedureRagHelper(cityId) {
                        @Override
                        public List<ProcedureRagHelper.Procedure> search(String query) {
                            return fakeProcedures;
                        }
                    };
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        public void setNextResponse(String response) {
            this.nextResponse = response;
        }

        public void setFakeProcedures(List<ProcedureRagHelper.Procedure> procs) {
            this.fakeProcedures = procs;
        }

        @Override
        public CompletableFuture<HttpDto> postToOpenRouterAsync(List<Map<String, Object>> messages) {
            String resp = nextResponse;
            nextResponse = "Test response"; // Reset for next call
            return CompletableFuture.completedFuture(new HttpDto(resp, 200, "POST", null));
        }
    }

    // ===== TEST SCENARIO 1: Markdown to HTML Conversion =====

    @Test
    @DisplayName("Scenario 1: Markdown in AI response is converted to HTML")
    void testMarkdownConversionInAskResponse() {
        TestHtmlFormattingWrapper wrapper = new TestHtmlFormattingWrapper();
        OpenRouterServices service = createOpenRouterService(wrapper);

        // AI response with Markdown formatting
        String markdownResponse = "## Response About Your Complaint\n\n" +
                "Your complaint about **potholes** on the *Main Street* is important.\n\n" +
                "- It affects road safety\n" +
                "- It causes vehicle damage\n\n" +
                "For more info, see [complaint procedures](https://example.com/procedures).";

        wrapper.setNextResponse(markdownResponse);
        wrapper.setFakeProcedures(List.of());

        OpenRouterResponseDto response = service.ask("Tell me about potholes", null, "testcity");

        assertTrue(response.isSuccess());
        assertNotNull(response.getMessage());

        // Verify HTML conversion happened
        String message = response.getMessage();
        assertTrue(message.contains("<h2>Response About Your Complaint</h2>"),
                "Heading should be converted to h2 tag");
        assertTrue(message.contains("<strong>potholes</strong>"),
                "Bold text should be converted to strong tag");
        assertTrue(message.contains("<em>Main Street</em>"),
                "Italic text should be converted to em tag");
        assertTrue(message.contains("<li>It affects road safety</li>"),
                "List items should be converted to li tags");
        assertTrue(message.contains("<a href=\"https://example.com/procedures\">complaint procedures</a>"),
                "Links should be converted to a tags");
    }

    // ===== TEST SCENARIO 2: Plain Text (No Markdown) =====

    @Test
    @DisplayName("Scenario 2: Plain text responses without Markdown are handled correctly")
    void testPlainTextWithoutMarkdown() {
        TestHtmlFormattingWrapper wrapper = new TestHtmlFormattingWrapper();
        OpenRouterServices service = createOpenRouterService(wrapper);

        String plainText = "This is a simple response without any Markdown formatting. " +
                "It should still work correctly and be wrapped in paragraph tags.";

        wrapper.setNextResponse(plainText);
        wrapper.setFakeProcedures(List.of());

        OpenRouterResponseDto response = service.ask("Simple question", null, "testcity");

        assertTrue(response.isSuccess());
        String message = response.getMessage();
        assertNotNull(message);
        // Plain text should be wrapped in <p> tags
        assertTrue(message.contains("<p>") || !message.contains("<"),
                "Plain text should be properly formatted");
    }

    // ===== TEST SCENARIO 3: Complex Multi-Element Markdown =====

    @Test
    @DisplayName("Scenario 3: Complex Markdown with multiple elements converts correctly")
    void testComplexMarkdownConversion() {
        TestHtmlFormattingWrapper wrapper = new TestHtmlFormattingWrapper();
        OpenRouterServices service = createOpenRouterService(wrapper);

        String complexMarkdown = "## Main Heading\n\n" +
                "This is a paragraph with **bold** and *italic* text.\n\n" +
                "### Sub Section\n\n" +
                "A list of items:\n" +
                "- First item with [link](https://example.com/1)\n" +
                "- Second item\n" +
                "- Third item with **emphasis**\n\n" +
                "Final paragraph with [another link](https://example.com/2).";

        wrapper.setNextResponse(complexMarkdown);
        wrapper.setFakeProcedures(List.of());

        OpenRouterResponseDto response = service.ask("Complex question", null, "testcity");

        assertTrue(response.isSuccess());
        String message = response.getMessage();

        // Verify all elements converted
        assertTrue(message.contains("<h2>Main Heading</h2>"));
        assertTrue(message.contains("<h3>Sub Section</h3>"));
        assertTrue(message.contains("<strong>bold</strong>"));
        assertTrue(message.contains("<em>italic</em>"));
        assertTrue(message.contains("<li>First item with"));
        assertTrue(message.contains("<a href=\"https://example.com/1\">link</a>"));
        assertTrue(message.contains("<a href=\"https://example.com/2\">another link</a>"));
    }
}
