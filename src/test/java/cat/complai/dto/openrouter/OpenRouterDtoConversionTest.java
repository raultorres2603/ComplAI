package cat.complai.dto.openrouter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DTO conversion and JSON serialization.
 * 
 * Verifies:
 * - OpenRouterPublicDto.from() correctly copies all fields from
 * OpenRouterResponseDto
 * - HTML content in message field is preserved during conversion
 * - Sources list is properly converted with all fields (url, title)
 * - JSON serialization includes all fields and maintains backward compatibility
 */
@MicronautTest
@DisplayName("OpenRouter DTO Conversion Tests")
@SuppressWarnings("unchecked")
public class OpenRouterDtoConversionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===== TEST 1: Basic Conversion =====

    @Test
    @DisplayName("from() copies success, message, error, errorCode fields")
    void testBasicFieldConversion() {
        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                true,
                "This is a test response",
                null,
                200,
                OpenRouterErrorCode.NONE);

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);

        assertNotNull(public_dto);
        assertTrue(public_dto.isSuccess());
        assertEquals("This is a test response", public_dto.getMessage());
        assertNull(public_dto.getError());
        assertEquals(0, public_dto.getErrorCode()); // NONE maps to 0
    }

    // ===== TEST 2: HTML Content Preservation =====

    @Test
    @DisplayName("from() preserves HTML content in message field")
    void testHtmlContentPreservation() {
        String htmlMessage = "<h2>Response Title</h2><p>This is a <strong>test</strong> with <em>HTML</em>.</p>" +
                "<ul><li>Item 1</li><li>Item 2</li></ul>";

        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                true,
                htmlMessage,
                null,
                200,
                OpenRouterErrorCode.NONE);

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);

        assertNotNull(public_dto.getMessage());
        assertEquals(htmlMessage, public_dto.getMessage());
        assertTrue(public_dto.getMessage().contains("<h2>"));
        assertTrue(public_dto.getMessage().contains("<strong>"));
    }

    // ===== TEST 3: Error Responses =====

    @Test
    @DisplayName("from() converts error responses correctly")
    void testErrorResponseConversion() {
        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                false,
                null,
                "This request is out of scope",
                422,
                OpenRouterErrorCode.REFUSAL);

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);

        assertNotNull(public_dto);
        assertFalse(public_dto.isSuccess());
        assertNull(public_dto.getMessage());
        assertEquals("This request is out of scope", public_dto.getError());
        assertEquals(2, public_dto.getErrorCode()); // REFUSAL = 2
    }

    // ===== TEST 4: Sources Conversion =====

    @Test
    @DisplayName("from() properly converts sources with url and title")
    void testSourcesConversion() {
        Source source1 = new Source("https://example.com/page1", "Example Page 1");
        Source source2 = new Source("https://example.com/page2", "Example Page 2");
        List<Source> sources = List.of(source1, source2);

        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                true,
                "Response with sources",
                null,
                200,
                OpenRouterErrorCode.NONE,
                null,
                sources);

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);

        assertNotNull(public_dto.getSources());
        assertEquals(2, public_dto.getSources().size());

        Source convertedSource1 = public_dto.getSources().get(0);
        assertEquals("https://example.com/page1", convertedSource1.getUrl());
        assertEquals("Example Page 1", convertedSource1.getTitle());

        Source convertedSource2 = public_dto.getSources().get(1);
        assertEquals("https://example.com/page2", convertedSource2.getUrl());
        assertEquals("Example Page 2", convertedSource2.getTitle());
    }

    // ===== TEST 5: Empty Sources =====

    @Test
    @DisplayName("from() handles empty sources list correctly")
    void testEmptySourcesConversion() {
        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                true,
                "Response without sources",
                null,
                200,
                OpenRouterErrorCode.NONE,
                null,
                List.of());

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);

        assertNotNull(public_dto.getSources());
        assertEquals(0, public_dto.getSources().size());
    }

    // ===== TEST 6: Null Input =====

    @Test
    @DisplayName("from() handles null input gracefully")
    void testNullInputConversion() {
        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(null);
        assertNull(public_dto);
    }

    // ===== TEST 7: JSON Serialization with Sources =====

    @Test
    @DisplayName("JSON serialization includes all fields: message, error, errorCode, sources")
    void testJsonSerializationWithSources() throws Exception {
        Source source1 = new Source("https://citydata.local/events", "City Events");
        Source source2 = new Source("https://citydata.local/procedures", "City Procedures");

        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                true,
                "Here are the upcoming events in the city.",
                null,
                200,
                OpenRouterErrorCode.NONE,
                null,
                List.of(source1, source2));

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);
        String json = objectMapper.writeValueAsString(public_dto);

        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

        // Verify all top-level fields are present
        assertTrue(parsed.containsKey("success"), "JSON should contain 'success' field");
        assertTrue(parsed.containsKey("message"), "JSON should contain 'message' field");
        assertTrue(parsed.containsKey("error"), "JSON should contain 'error' field");
        assertTrue(parsed.containsKey("errorCode"), "JSON should contain 'errorCode' field");
        assertTrue(parsed.containsKey("sources"), "JSON should contain 'sources' field");

        // Verify field values
        assertEquals(true, parsed.get("success"));
        assertEquals("Here are the upcoming events in the city.", parsed.get("message"));
        assertNull(parsed.get("error"));
        assertEquals(0, parsed.get("errorCode"));

        // Verify sources array
        List<Map<String, Object>> sourcesArray = (List<Map<String, Object>>) parsed.get("sources");
        assertNotNull(sourcesArray);
        assertEquals(2, sourcesArray.size());

        Map<String, Object> firstSource = sourcesArray.get(0);
        assertEquals("https://citydata.local/events", firstSource.get("url"));
        assertEquals("City Events", firstSource.get("title"));

        Map<String, Object> secondSource = sourcesArray.get(1);
        assertEquals("https://citydata.local/procedures", secondSource.get("url"));
        assertEquals("City Procedures", secondSource.get("title"));
    }

    // ===== TEST 8: JSON Serialization - Error Response with Sources =====

    @Test
    @DisplayName("JSON serialization of error response includes all fields")
    void testJsonSerializationErrorResponse() throws Exception {
        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                false,
                null,
                "Request is not about El Prat de Llobregat",
                200,
                OpenRouterErrorCode.REFUSAL,
                null,
                List.of());

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);
        String json = objectMapper.writeValueAsString(public_dto);

        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

        assertEquals(false, parsed.get("success"));
        assertNull(parsed.get("message"));
        assertEquals("Request is not about El Prat de Llobregat", parsed.get("error"));
        assertEquals(2, parsed.get("errorCode")); // REFUSAL = 2
        assertEquals(0, ((List<?>) parsed.get("sources")).size());
    }

    // ===== TEST 9: Source Immutability =====

    @Test
    @DisplayName("sources list in public DTO is immutable")
    void testSourcesImmutability() {
        Source source1 = new Source("https://example.com/1", "Page 1");
        List<Source> sources = List.of(source1);

        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                true,
                "Message",
                null,
                200,
                OpenRouterErrorCode.NONE,
                null,
                sources);

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);

        // Attempt to modify should throw exception
        assertThrows(UnsupportedOperationException.class, () -> {
            public_dto.getSources().add(new Source("https://example.com/2", "Page 2"));
        });
    }

    // ===== TEST 10: Complex HTML and Markdown Content =====

    @Test
    @DisplayName("Complex HTML content with special characters is preserved")
    void testComplexHtmlPreservation() {
        String complexHtml = "<h2>Response Title</h2>" +
                "<p>This mentions &amp; symbols, &lt;tags&gt;, and quotes.</p>" +
                "<a href=\"https://example.com?param1=value&amp;param2=value2\">Link with params</a>";

        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                true,
                complexHtml,
                null,
                200,
                OpenRouterErrorCode.NONE);

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);

        assertEquals(complexHtml, public_dto.getMessage());

        // Verify it serializes correctly
        assertDoesNotThrow(() -> {
            String json = objectMapper.writeValueAsString(public_dto);
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            assertEquals(complexHtml, parsed.get("message"));
        });
    }

    // ===== TEST 11: ErrorCode Conversion =====

    @Test
    @DisplayName("ErrorCode enum is correctly mapped to integer code")
    void testErrorCodeConversion() {
        testErrorCodeMapping(OpenRouterErrorCode.NONE, 0);
        testErrorCodeMapping(OpenRouterErrorCode.VALIDATION, 1);
        testErrorCodeMapping(OpenRouterErrorCode.REFUSAL, 2);
        testErrorCodeMapping(OpenRouterErrorCode.UPSTREAM, 3);
        testErrorCodeMapping(OpenRouterErrorCode.TIMEOUT, 4);
        testErrorCodeMapping(OpenRouterErrorCode.INTERNAL, 5);
        testErrorCodeMapping(OpenRouterErrorCode.UNAUTHORIZED, 6);
    }

    private void testErrorCodeMapping(OpenRouterErrorCode errorCode, int expectedCode) {
        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                false,
                null,
                "Error message",
                500,
                errorCode);

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);
        assertEquals(expectedCode, public_dto.getErrorCode(),
                "ErrorCode " + errorCode + " should map to " + expectedCode);
    }

    // ===== TEST 12: Full Integration - Message with Sources =====

    @Test
    @DisplayName("Full integration: HTML message with multiple sources serializes correctly")
    void testFullIntegration() throws Exception {
        String htmlMessage = "<h3>Event Information</h3>" +
                "<p>The following <strong>events</strong> are happening:</p>" +
                "<ul><li>Festival on May 5</li><li>Market on Saturdays</li></ul>";

        Source source1 = new Source("https://cityevents.local/list", "Events Calendar");
        Source source2 = new Source("https://cityprocedures.local/registration", "Registration Rules");

        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                true,
                htmlMessage,
                null,
                200,
                OpenRouterErrorCode.NONE,
                null,
                List.of(source1, source2));

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);

        // Verify conversion
        assertTrue(public_dto.isSuccess());
        assertEquals(htmlMessage, public_dto.getMessage());
        assertEquals(2, public_dto.getSources().size());

        // Verify JSON serialization
        String json = objectMapper.writeValueAsString(public_dto);
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

        assertEquals(true, parsed.get("success"));
        assertEquals(htmlMessage, parsed.get("message"));
        List<Map<String, Object>> sourcesArray = (List<Map<String, Object>>) parsed.get("sources");
        assertEquals(2, sourcesArray.size());
        assertEquals("https://cityevents.local/list", sourcesArray.get(0).get("url"));
        assertEquals("Events Calendar", sourcesArray.get(0).get("title"));
    }

    // ===== TEST 13: Verify pdfData is NOT in public DTO =====

    @Test
    @DisplayName("PDF data field is not exposed in public DTO JSON")
    void testPdfDataNotExposedInJson() throws Exception {
        byte[] pdfData = new byte[] { 1, 2, 3, 4, 5 };

        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                true,
                "Response with embedded PDF",
                null,
                200,
                OpenRouterErrorCode.NONE,
                pdfData,
                List.of());

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);
        String json = objectMapper.writeValueAsString(public_dto);

        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

        // Verify pdfData is NOT in the JSON output
        assertFalse(parsed.containsKey("pdfData"),
                "JSON should NOT expose internal pdfData field");
        assertFalse(parsed.containsKey("pdf"),
                "JSON should NOT expose any pdf-related field");
    }

    // ===== TEST 14: Verify statusCode is NOT in public DTO =====

    @Test
    @DisplayName("HTTP status code is not exposed in public DTO JSON")
    void testStatusCodeNotExposedInJson() throws Exception {
        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                true,
                "Response",
                null,
                200, // statusCode field
                OpenRouterErrorCode.NONE,
                null,
                List.of());

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);
        String json = objectMapper.writeValueAsString(public_dto);

        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

        // Verify statusCode is NOT in the JSON output
        assertFalse(parsed.containsKey("statusCode"),
                "JSON should NOT expose internal statusCode field");
    }

    // ===== TEST 15: Public API contract verification =====

    @Test
    @DisplayName("Public API contract only includes: success, message, error, errorCode, sources")
    void testPublicApiContract() throws Exception {
        Source source = new Source("https://api.example.com/resource", "Resource Title");

        OpenRouterResponseDto internal = new OpenRouterResponseDto(
                true,
                "<h2>Title</h2><p>Content</p>",
                null,
                200,
                OpenRouterErrorCode.NONE,
                new byte[] { 1, 2, 3 }, // Should NOT be exposed
                List.of(source));

        OpenRouterPublicDto public_dto = OpenRouterPublicDto.from(internal);
        String json = objectMapper.writeValueAsString(public_dto);

        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

        // Verify ONLY the expected fields are present
        assertEquals(5, parsed.size(),
                "Public DTO should only have 5 fields: success, message, error, errorCode, sources");

        // Verify all required fields are present
        assertTrue(parsed.containsKey("success"));
        assertTrue(parsed.containsKey("message"));
        assertTrue(parsed.containsKey("error"));
        assertTrue(parsed.containsKey("errorCode"));
        assertTrue(parsed.containsKey("sources"));

        // Verify internal fields are NOT exposed
        assertFalse(parsed.containsKey("statusCode"));
        assertFalse(parsed.containsKey("pdfData"));
        assertFalse(parsed.containsKey("pdf"));
    }
}
