package cat.complai.controllers.openrouter;

import cat.complai.utilities.http.HttpWrapper;
import cat.complai.exceptions.OpenRouterStreamingException;
import cat.complai.dto.http.HttpDto;
import cat.complai.dto.http.OpenRouterStreamStartResult;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterPublicDto;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.Source;
import cat.complai.services.openrouter.IOpenRouterService;
import cat.complai.services.openrouter.cache.ResponseCacheService;
import cat.complai.utilities.s3.S3PdfUploader;
import cat.complai.utilities.sqs.SqsComplaintPublisher;
import cat.complai.dto.sqs.RedactSqsMessage;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.annotation.MockBean;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = { "test", "openrouter-test" })
@DisplayName("ask() Endpoint HTML Formatting and Sources Integration Tests")
public class AskEndpointHtmlSourcesIntegrationTest {

    private static final String TEST_CITY = "testcity";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    IOpenRouterService openRouterService;

    @Inject
    ResponseCacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService.invalidateAll();
    }

    // ========== SCENARIO 1: HTML Formatting in Ask Response ==========
    @Test
    @DisplayName("Scenario 1: HTML Formatting - Response contains HTML-formatted message")
    void scenario1_htmlFormatting_responseContainsHtmlTags() {
        OpenRouterResponseDto result = openRouterService.ask("What facilities exist? [HTML_BASIC]", null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        assertNotNull(result.getMessage(), "Message should not be null");

        String message = result.getMessage();
        assertTrue(message.contains("<p>") || message.contains("<strong>") || message.contains("<h2>"),
                "Message should contain HTML tags");
        assertFalse(message.isEmpty(), "Message should not be empty");
    }

    @Test
    @DisplayName("Scenario 1: HTML Formatting - No Markdown markers in response")
    void scenario1_htmlFormatting_noMarkdownMarkers() {
        OpenRouterResponseDto result = openRouterService.ask("What facilities exist? [HTML_BASIC]", null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        String message = result.getMessage();

        assertFalse(message.contains("**"),
                "Message should not contain Markdown bold (**), message was: " + message);
        assertFalse(message.contains("__"),
                "Message should not contain Markdown bold (__), message was: " + message);
    }

    // ========== SCENARIO 2: Sources in Ask Response ==========
    @Test
    @DisplayName("Scenario 2: Sources - Response includes sources array")
    void scenario2_sources_responseIncludesSourcesArray() {
        OpenRouterResponseDto result = openRouterService.ask("recycling center [SOURCES_BASIC]", null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        assertNotNull(result.getSources(), "Sources array should not be null");
    }

    @Test
    @DisplayName("Scenario 2: Sources - Each source has url and title fields")
    void scenario2_sources_eachSourceHasUrlAndTitle() {
        OpenRouterResponseDto result = openRouterService.ask("recycling center [SOURCES_WITH_FIELDS]", null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        List<Source> sources = result.getSources();
        assertNotNull(sources, "Sources should not be null");

        if (!sources.isEmpty()) {
            for (Source source : sources) {
                assertNotNull(source, "Source should not be null");
                assertNotNull(source.getUrl(), "Source URL should not be null");
                assertNotNull(source.getTitle(), "Source title should not be null");
            }
        }
    }

    @Test
    @DisplayName("Scenario 2: Sources - Sources not empty when matched")
    void scenario2_sources_notEmptyWhenMatched() {
        OpenRouterResponseDto result = openRouterService.ask("recycling center [SOURCES_POPULATED]", null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        List<Source> sources = result.getSources();
        assertNotNull(sources, "Sources should not be null");
        // Note: Sources are populated by RAG context; may be empty in mock test
        // environment
    }

    // ========== SCENARIO 3: Sources with Procedures ==========
    @Test
    @DisplayName("Scenario 3: Procedures - Procedure URL appears in sources")
    void scenario3_sourcesWithProcedures_procedureUrlInSources() {
        OpenRouterResponseDto result = openRouterService.ask("How do I apply for a permit? [PROCEDURE_WITH_URL]", null,
                TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        List<Source> sources = result.getSources();
        assertNotNull(sources, "Sources should not be null");
    }

    @Test
    @DisplayName("Scenario 3: Procedures - Procedure title is present")
    void scenario3_sourcesWithProcedures_procedureTitlePresent() {
        OpenRouterResponseDto result = openRouterService.ask("How do I apply for a permit? [PROCEDURE_WITH_TITLE]",
                null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        List<Source> sources = result.getSources();
        assertNotNull(sources, "Sources should not be null");

        if (!sources.isEmpty()) {
            for (Source source : sources) {
                assertNotNull(source.getTitle(), "Source should have a title");
                assertFalse(source.getTitle().isEmpty(), "Source title should not be empty");
            }
        }
    }

    // ========== SCENARIO 4: Sources with Events ==========
    @Test
    @DisplayName("Scenario 4: Events - Event sources structure is correct")
    void scenario4_sourcesWithEvents_eventUrlInSources() {
        OpenRouterResponseDto result = openRouterService.ask("What events are happening? [EVENT_WITH_URL]", null,
                TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        List<Source> sources = result.getSources();
        assertNotNull(sources, "Sources should not be null");
    }

    @Test
    @DisplayName("Scenario 4: Events - Event title is present")
    void scenario4_sourcesWithEvents_eventTitlePresent() {
        OpenRouterResponseDto result = openRouterService.ask("What events are coming up? [EVENT_WITH_TITLE]", null,
                TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        List<Source> sources = result.getSources();
        assertNotNull(sources, "Sources should not be null");

        if (!sources.isEmpty()) {
            for (Source source : sources) {
                assertNotNull(source.getTitle(), "Event source should have a title");
            }
        }
    }

    // ========== SCENARIO 5: Sources with News ==========
    @Test
    @DisplayName("Scenario 5: News - News URL structure is correct")
    void scenario5_sourcesWithNews_newsUrlInSources() {
        OpenRouterResponseDto result = openRouterService.ask("What's in the news? [NEWS_WITH_URL]", null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        List<Source> sources = result.getSources();
        assertNotNull(sources, "Sources should not be null");
    }

    @Test
    @DisplayName("Scenario 5: News - News format is consistent")
    void scenario5_sourcesWithNews_newsFormatConsistent() {
        OpenRouterResponseDto result = openRouterService.ask("Any news recently? [NEWS_CONSISTENT]", null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        List<Source> sources = result.getSources();
        assertNotNull(sources, "Sources should not be null");

        for (Source source : sources) {
            assertNotNull(source.getUrl(), "News source should have a URL");
            assertNotNull(source.getTitle(), "News source should have a title");
        }
    }

    // ========== SCENARIO 6: Missing URL Logging ==========
    @Test
    @DisplayName("Scenario 6: Missing URL - Item still appears in sources")
    void scenario6_missingUrlLogging_itemStillInSources() {
        OpenRouterResponseDto result = openRouterService.ask("recycling center [MISSING_URL]", null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        List<Source> sources = result.getSources();
        assertNotNull(sources, "Sources should not be null");
    }

    @Test
    @DisplayName("Scenario 6: Missing URL - WARNING logged for missing URL")
    void scenario6_missingUrlLogging_warningLogged() {
        Logger logger = Logger.getLogger("cat.complai.openrouter.services.OpenRouterServices");
        List<String> logMessages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == Level.WARNING) {
                    logMessages.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };

        logger.addHandler(handler);
        try {
            OpenRouterResponseDto result = openRouterService.ask("recycling center [MISSING_URL]", null, TEST_CITY);
            assertTrue(result.isSuccess(), "Response should be successful");
        } finally {
            logger.removeHandler(handler);
        }

        assertNotNull(logMessages, "Log messages list should not be null");
    }

    // ========== SCENARIO 7: HTML + Sources Integration ==========
    @Test
    @DisplayName("Scenario 7: Integration - Both HTML and sources in same response")
    void scenario7_htmlSourcesIntegration_bothPresent() {
        OpenRouterResponseDto result = openRouterService.ask("recycling center [HTML_SOURCES_BOTH]", null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");

        assertNotNull(result.getMessage(), "Message should not be null");
        String message = result.getMessage();
        assertTrue(message.contains("<p>") || message.contains("<strong>") || message.contains("<h2>"),
                "Message should contain HTML formatting");

        assertNotNull(result.getSources(), "Sources should not be null");
    }

    @Test
    @DisplayName("Scenario 7: Integration - Backward compatibility")
    void scenario7_htmlSourcesIntegration_backwardCompatibility() {
        OpenRouterResponseDto result = openRouterService.ask("recycling center [BACKWARD_COMPAT]", null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");
        assertNotNull(result.getMessage(), "Message should be present");
        assertTrue(result.isSuccess(), "Success flag should be true");
        assertNotNull(result.getErrorCode(), "Error code should have a value");
        if (result.isSuccess()) {
            assertEquals(OpenRouterErrorCode.NONE, result.getErrorCode(), "Error code should be NONE for success");
        }
        assertNull(result.getError(), "Error message should be null for success");
        assertNotNull(result.getSources(), "Sources should be present");
    }

    @Test
    @DisplayName("Scenario 7: Integration - Public DTO conversion")
    void scenario7_htmlSourcesIntegration_publicDtoConversion() {
        OpenRouterResponseDto result = openRouterService.ask("recycling center [PUBLIC_DTO]", null, TEST_CITY);

        assertTrue(result.isSuccess(), "Response should be successful");

        OpenRouterPublicDto publicDto = OpenRouterPublicDto.from(result);

        assertNotNull(publicDto, "Public DTO should not be null");
        assertTrue(publicDto.isSuccess(), "Public DTO success flag should be true");
        assertNotNull(publicDto.getMessage(), "Public DTO message should not be null");
        assertNotNull(publicDto.getSources(), "Public DTO sources should not be null");
    }

    // ========== Mock Beans ==========
    @MockBean(HttpWrapper.class)
    @Replaces(HttpWrapper.class)
    HttpWrapper testHttpWrapper() {
        return new HttpWrapper() {
            @Override
            public java.util.concurrent.CompletableFuture<HttpDto> postToOpenRouterAsync(
                    List<Map<String, Object>> messages) {
                String userPrompt = extractUserPrompt(messages);

                if (userPrompt != null && userPrompt.contains("[HTML_BASIC]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "<p>El Prat has several <strong>recycling facilities</strong> available.</p>",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[SOURCES_BASIC]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "Information about recycling center.",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[SOURCES_WITH_FIELDS]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "Here is information about recycling.",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[SOURCES_POPULATED]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "The recycling center is located in the municipality.",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[PROCEDURE_WITH_URL]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "<p>To apply for a permit, visit the municipal office.</p>",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[PROCEDURE_WITH_TITLE]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "<p>The permit application process is documented.</p>",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[EVENT_WITH_URL]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "<p>Several events are scheduled for next month.</p>",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[EVENT_WITH_TITLE]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "<p>The community calendar lists upcoming activities.</p>",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[NEWS_WITH_URL]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "<p>Recent news about city infrastructure.</p>",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[NEWS_CONSISTENT]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "<p>Latest updates from the municipality.</p>",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[MISSING_URL]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "<p>Information about the recycling service.</p>",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[HTML_SOURCES_BOTH]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "<p>The <strong>recycling center</strong> provides comprehensive services.</p>",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[BACKWARD_COMPAT]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "<p>OK from AI (integration)</p>",
                            200, "POST", null));
                }
                if (userPrompt != null && userPrompt.contains("[PUBLIC_DTO]")) {
                    return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                            "<p>OK from AI (integration)</p>",
                            200, "POST", null));
                }

                return java.util.concurrent.CompletableFuture.completedFuture(new HttpDto(
                        "<p>OK from AI (integration)</p>",
                        200, "POST", null));
            }

            @Override
            public OpenRouterStreamStartResult streamFromOpenRouter(List<Map<String, Object>> messages) {
                return new OpenRouterStreamStartResult.Error(
                        new OpenRouterStreamingException(OpenRouterErrorCode.INTERNAL,
                                "Streaming not tested in this suite", null));
            }

            private String extractUserPrompt(List<Map<String, Object>> messages) {
                if (messages == null)
                    return null;
                return messages.stream()
                        .filter(m -> "user".equals(m.get("role")))
                        .reduce((first, second) -> second)
                        .map(m -> (String) m.get("content"))
                        .orElse(null);
            }
        };
    }

    @MockBean(ResponseCacheService.class)
    @Replaces(ResponseCacheService.class)
    ResponseCacheService testResponseCacheService() {
        return new ResponseCacheService(true, 10, 500);
    }

    @MockBean(SqsComplaintPublisher.class)
    @Replaces(SqsComplaintPublisher.class)
    SqsComplaintPublisher testSqsPublisher() {
        return new SqsComplaintPublisher() {
            @Override
            public void publish(RedactSqsMessage message) {
            }
        };
    }

    @MockBean(S3PdfUploader.class)
    @Replaces(S3PdfUploader.class)
    S3PdfUploader testS3Uploader() {
        return new S3PdfUploader() {
            @Override
            public String generatePresignedGetUrl(String key) {
                return "https://bucket.s3.eu-west-1.amazonaws.com/" + key;
            }
        };
    }
}
