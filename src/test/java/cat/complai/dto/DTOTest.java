package cat.complai.dto;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import cat.complai.controllers.feedback.dto.FeedbackAcceptedDto;
import cat.complai.controllers.feedback.dto.FeedbackRequest;
import cat.complai.controllers.openrouter.dto.AskRequest;
import cat.complai.dto.feedback.FeedbackErrorCode;
import cat.complai.dto.feedback.FeedbackResult;
import cat.complai.dto.feedback.FeedbackSqsMessage;
import cat.complai.dto.home.HealthDto;
import cat.complai.dto.home.HomeDto;
import cat.complai.dto.http.HttpDto;
import cat.complai.dto.openrouter.AskStreamResult;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterPublicDto;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.OutputFormat;
import cat.complai.dto.openrouter.RedactAcceptedDto;
import cat.complai.dto.openrouter.Source;
import cat.complai.dto.openrouter.sse.SseChunkEvent;
import cat.complai.dto.openrouter.sse.SseDoneEvent;
import cat.complai.dto.openrouter.sse.SseErrorEvent;
import cat.complai.dto.openrouter.sse.SseSources;
import cat.complai.dto.openrouter.sse.SseSourcesEvent;
import cat.complai.dto.sqs.RedactSqsMessage;
import cat.complai.exceptions.OpenRouterStreamingException;
import cat.complai.utilities.auth.OidcConfig;
import cat.complai.utilities.auth.VerifiedCitizenIdentity;
import cat.complai.utilities.cache.QuestionCategory;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DTO Tests")
class DTOTest {

    // =========================================================================
    // SSE DTOs
    // =========================================================================

    @Nested
    @DisplayName("SseChunkEvent Tests")
    class SseChunkEventTests {
        @Test
        @DisplayName("Should create SseChunkEvent with content via shorthand constructor")
        void shouldCreateWithContent() {
            SseChunkEvent event = new SseChunkEvent("Hello");
            assertEquals("chunk", event.type());
            assertEquals("Hello", event.content());
        }

        @Test
        @DisplayName("Should create SseChunkEvent with full constructor")
        void shouldCreateWithFullConstructor() {
            SseChunkEvent event = new SseChunkEvent("custom", "data");
            assertEquals("custom", event.type());
            assertEquals("data", event.content());
        }

        @Test
        @DisplayName("Should support toString")
        void shouldSupportToString() {
            SseChunkEvent event = new SseChunkEvent("test");
            assertNotNull(event.toString());
        }
    }

    @Nested
    @DisplayName("SseDoneEvent Tests")
    class SseDoneEventTests {
        @Test
        @DisplayName("Should create SseDoneEvent with conversationId")
        void shouldCreateWithConversationId() {
            SseDoneEvent event = new SseDoneEvent("conv-123");
            assertEquals("done", event.type());
            assertEquals("conv-123", event.conversationId());
        }

        @Test
        @DisplayName("Should create SseDoneEvent with null conversationId")
        void shouldCreateWithNullConversationId() {
            SseDoneEvent event = new SseDoneEvent(null);
            assertEquals("done", event.type());
            assertNull(event.conversationId());
        }
    }

    @Nested
    @DisplayName("SseErrorEvent Tests")
    class SseErrorEventTests {
        @Test
        @DisplayName("Should create with error only")
        void shouldCreateWithErrorOnly() {
            SseErrorEvent event = new SseErrorEvent("Something went wrong");
            assertEquals("error", event.type());
            assertEquals("Something went wrong", event.error());
            assertNull(event.errorCode());
        }

        @Test
        @DisplayName("Should create with error and errorCode")
        void shouldCreateWithErrorAndCode() {
            SseErrorEvent event = new SseErrorEvent("Not found", 404);
            assertEquals("error", event.type());
            assertEquals("Not found", event.error());
            assertEquals(404, event.errorCode());
        }

        @Test
        @DisplayName("Should create with full constructor")
        void shouldCreateWithFullConstructor() {
            SseErrorEvent event = new SseErrorEvent("custom", "msg", 1);
            assertEquals("custom", event.type());
            assertEquals("msg", event.error());
            assertEquals(1, event.errorCode());
        }
    }

    @Nested
    @DisplayName("SseSourcesEvent Tests")
    class SseSourcesEventTests {
        @Test
        @DisplayName("Should create with sources list")
        void shouldCreateWithSources() {
            List<SseSources> sources = List.of(new SseSources("Title", "url"));
            SseSourcesEvent event = new SseSourcesEvent(sources);
            assertEquals("sources", event.type());
            assertEquals(1, event.sources().size());
        }

        @Test
        @DisplayName("Should handle null sources as empty list")
        void shouldHandleNullSources() {
            SseSourcesEvent event = new SseSourcesEvent(null);
            assertEquals("sources", event.type());
            assertNotNull(event.sources());
            assertTrue(event.sources().isEmpty());
        }

        @Test
        @DisplayName("Should create with full constructor")
        void shouldCreateWithFullConstructor() {
            SseSourcesEvent event = new SseSourcesEvent("custom", List.of(new SseSources("T", "u")));
            assertEquals("custom", event.type());
            assertEquals(1, event.sources().size());
        }
    }

    @Nested
    @DisplayName("SseSources Tests")
    class SseSourcesTests {
        @Test
        @DisplayName("Should create with title and url")
        void shouldCreateWithTitleAndUrl() {
            SseSources s = new SseSources("Procedure Title", "https://example.com/proc");
            assertEquals("Procedure Title", s.title());
            assertEquals("https://example.com/proc", s.url());
        }

        @Test
        @DisplayName("Should support null url")
        void shouldSupportNullUrl() {
            SseSources s = new SseSources("Title", null);
            assertEquals("Title", s.title());
            assertNull(s.url());
        }
    }

    // =========================================================================
    // Auth DTOs
    // =========================================================================

    @Nested
    @DisplayName("VerifiedCitizenIdentity Tests")
    class VerifiedCitizenIdentityTests {
        @Test
        @DisplayName("Should create with name, surname, nif")
        void shouldCreateWithAllFields() {
            VerifiedCitizenIdentity id = new VerifiedCitizenIdentity("Joan", "Garcia", "12345678Z");
            assertEquals("Joan", id.name());
            assertEquals("Garcia", id.surname());
            assertEquals("12345678Z", id.nif());
        }
    }

    @Nested
    @DisplayName("OidcConfig Tests")
    class OidcConfigTests {
        @Test
        @DisplayName("Should create OidcConfig record")
        void shouldCreateOidcConfig() {
            OidcConfig config = new OidcConfig(true, "https://issuer", "https://jwks", "audience", "nif");
            assertTrue(config.enabled());
            assertEquals("https://issuer", config.issuer());
            assertEquals("https://jwks", config.jwksUri());
            assertEquals("audience", config.audience());
            assertEquals("nif", config.nifClaim());
        }

        @Test
        @DisplayName("Should create disabled OidcConfig")
        void shouldCreateDisabledConfig() {
            OidcConfig config = new OidcConfig(false, "", "", "", "");
            assertFalse(config.enabled());
        }
    }

    // =========================================================================
    // Feedback DTOs
    // =========================================================================

    @Nested
    @DisplayName("FeedbackResult Tests")
    class FeedbackResultTests {
        @Test
        @DisplayName("Should create Success result")
        void shouldCreateSuccess() {
            FeedbackAcceptedDto dto = new FeedbackAcceptedDto("fb-1", "queued", "ok");
            FeedbackResult result = new FeedbackResult.Success(dto);
            assertInstanceOf(FeedbackResult.Success.class, result);
            assertEquals("fb-1", ((FeedbackResult.Success) result).data().feedbackId());
        }

        @Test
        @DisplayName("Should create Error result")
        void shouldCreateError() {
            FeedbackResult result = new FeedbackResult.Error(FeedbackErrorCode.VALIDATION, "Invalid");
            assertInstanceOf(FeedbackResult.Error.class, result);
            assertEquals(FeedbackErrorCode.VALIDATION, ((FeedbackResult.Error) result).errorCode());
            assertEquals("Invalid", ((FeedbackResult.Error) result).message());
        }
    }

    @Nested
    @DisplayName("FeedbackErrorCode Tests")
    class FeedbackErrorCodeTests {
        @Test
        @DisplayName("Should have expected enum constants")
        void shouldHaveExpectedConstants() {
            assertEquals(3, FeedbackErrorCode.values().length);
        }

        @Test
        @DisplayName("Should return correct codes")
        void shouldReturnCorrectCodes() {
            assertEquals(100, FeedbackErrorCode.VALIDATION.getCode());
            assertEquals(101, FeedbackErrorCode.QUEUE_PUBLISH_FAILED.getCode());
            assertEquals(102, FeedbackErrorCode.INTERNAL.getCode());
        }

        @Test
        @DisplayName("toValue should return numeric code")
        void toValueShouldReturnCode() {
            assertEquals(100, FeedbackErrorCode.VALIDATION.toValue());
        }

        @Test
        @DisplayName("valueOf should resolve valid constants")
        void valueOfShouldResolve() {
            assertEquals(FeedbackErrorCode.VALIDATION, FeedbackErrorCode.valueOf("VALIDATION"));
            assertEquals(FeedbackErrorCode.INTERNAL, FeedbackErrorCode.valueOf("INTERNAL"));
        }
    }

    @Nested
    @DisplayName("FeedbackSqsMessage Tests")
    class FeedbackSqsMessageTests {
        @Test
        @DisplayName("Should create with constructor")
        void shouldCreateWithConstructor() {
            FeedbackSqsMessage msg = new FeedbackSqsMessage("fb-1", 1000L, "elprat", "Joan", "user1", "Great!");
            assertEquals("fb-1", msg.feedbackId());
            assertEquals(1000L, msg.timestamp());
            assertEquals("elprat", msg.city());
            assertEquals("Joan", msg.userName());
            assertEquals("user1", msg.idUser());
            assertEquals("Great!", msg.message());
        }

        @Test
        @DisplayName("Should create via fromJson")
        void shouldCreateViaFromJson() {
            FeedbackSqsMessage msg = FeedbackSqsMessage.fromJson("fb-2", 2000L, "testcity", "Maria", "user2", "OK");
            assertEquals("fb-2", msg.feedbackId());
            assertEquals(2000L, msg.timestamp());
        }
    }

    @Nested
    @DisplayName("FeedbackRequest Tests")
    class FeedbackRequestTests {
        @Test
        @DisplayName("Should create FeedbackRequest")
        void shouldCreateFeedbackRequest() {
            FeedbackRequest req = new FeedbackRequest("Joan", "user1", "Great service");
            assertEquals("Joan", req.userName());
            assertEquals("user1", req.idUser());
            assertEquals("Great service", req.message());
        }
    }

    @Nested
    @DisplayName("FeedbackAcceptedDto Tests")
    class FeedbackAcceptedDtoTests {
        @Test
        @DisplayName("Should create FeedbackAcceptedDto")
        void shouldCreateFeedbackAcceptedDto() {
            FeedbackAcceptedDto dto = new FeedbackAcceptedDto("fb-1", "queued", "Feedback received");
            assertEquals("fb-1", dto.feedbackId());
            assertEquals("queued", dto.status());
            assertEquals("Feedback received", dto.message());
        }
    }

    // =========================================================================
    // HTTP DTOs
    // =========================================================================

    @Nested
    @DisplayName("HttpDto Tests")
    class HttpDtoTests {
        @Test
        @DisplayName("Should create HttpDto with all fields")
        void shouldCreateHttpDto() {
            HttpDto dto = new HttpDto("response body", 200, "POST", null);
            assertEquals("response body", dto.message());
            assertEquals(200, dto.statusCode());
            assertEquals("POST", dto.method());
            assertNull(dto.error());
        }

        @Test
        @DisplayName("Should create HttpDto with error")
        void shouldCreateHttpDtoWithError() {
            HttpDto dto = new HttpDto(null, 500, "POST", "Internal error");
            assertNull(dto.message());
            assertEquals(500, dto.statusCode());
            assertEquals("Internal error", dto.error());
        }
    }

    // =========================================================================
    // OpenRouter DTOs
    // =========================================================================

    @Nested
    @DisplayName("OpenRouterStreamStartResult Tests")
    class OpenRouterStreamStartResultTests {
        @Test
        @DisplayName("Should create Success result")
        void shouldCreateSuccess() {
            var success = new cat.complai.dto.http.OpenRouterStreamStartResult.Success(
                    Flux.just("data"));
            assertNotNull(success.stream());
        }

        @Test
        @DisplayName("Should create Error result")
        void shouldCreateError() {
            var ex = new OpenRouterStreamingException(OpenRouterErrorCode.UPSTREAM,
                    "Upstream error", 502);
            var error = new cat.complai.dto.http.OpenRouterStreamStartResult.Error(ex);
            assertEquals(ex, error.failure());
            assertEquals(OpenRouterErrorCode.UPSTREAM, error.failure().getErrorCode());
        }
    }

    @Nested
    @DisplayName("AskStreamResult Tests")
    class AskStreamResultTests {
        @Test
        @DisplayName("Should create Success result")
        void shouldCreateSuccess() {
            var success = new AskStreamResult.Success(Flux.just("data"));
            assertNotNull(success.stream());
        }

        @Test
        @DisplayName("Should create Error result")
        void shouldCreateError() {
            OpenRouterResponseDto errorDto = new OpenRouterResponseDto(false, null,
                    "error", 400, OpenRouterErrorCode.VALIDATION);
            var error = new AskStreamResult.Error(errorDto);
            assertEquals(errorDto, error.errorResponse());
        }
    }

    @Nested
    @DisplayName("OpenRouterResponseDto Tests")
    class OpenRouterResponseDtoTests {
        @Test
        @DisplayName("Should create with 5-arg constructor")
        void shouldCreateWithFiveArgConstructor() {
            OpenRouterResponseDto dto = new OpenRouterResponseDto(true, "message", null, 200,
                    OpenRouterErrorCode.NONE);
            assertTrue(dto.isSuccess());
            assertEquals("message", dto.getMessage());
            assertNull(dto.getError());
            assertEquals(200, dto.getStatusCode());
            assertEquals(OpenRouterErrorCode.NONE, dto.getErrorCode());
            assertNull(dto.getPdfData());
            assertNotNull(dto.getSources());
            assertTrue(dto.getSources().isEmpty());
        }

        @Test
        @DisplayName("Should create with 6-arg constructor including pdfData")
        void shouldCreateWithSixArgConstructor() {
            byte[] pdf = new byte[]{1, 2, 3};
            OpenRouterResponseDto dto = new OpenRouterResponseDto(true, "msg", null, 200,
                    OpenRouterErrorCode.NONE, pdf);
            assertTrue(dto.isSuccess());
            assertEquals(pdf, dto.getPdfData());
        }

        @Test
        @DisplayName("Should create with 7-arg constructor")
        void shouldCreateWithSevenArgConstructor() {
            List<Source> sources = List.of(new Source("https://example.com", "Title"));
            OpenRouterResponseDto dto = new OpenRouterResponseDto(true, "msg", null, 200,
                    OpenRouterErrorCode.NONE, null, sources);
            assertEquals(1, dto.getSources().size());
        }

        @Test
        @DisplayName("Should default null errorCode to NONE")
        void shouldDefaultNullErrorCodeToNone() {
            OpenRouterResponseDto dto = new OpenRouterResponseDto(true, "msg", null, 200, null);
            assertEquals(OpenRouterErrorCode.NONE, dto.getErrorCode());
        }

        @Test
        @DisplayName("Should default null sources to empty list")
        void shouldDefaultNullSourcesToEmpty() {
            OpenRouterResponseDto dto = new OpenRouterResponseDto(true, "msg", null, 200,
                    OpenRouterErrorCode.NONE, null, null);
            assertNotNull(dto.getSources());
            assertTrue(dto.getSources().isEmpty());
        }
    }

    @Nested
    @DisplayName("OpenRouterPublicDto Tests")
    class OpenRouterPublicDtoTests {
        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            List<Source> sources = List.of(new Source("https://example.com", "Title"));
            OpenRouterPublicDto dto = new OpenRouterPublicDto(true, "message", null, 0, sources);
            assertTrue(dto.isSuccess());
            assertEquals("message", dto.getMessage());
            assertNull(dto.getError());
            assertEquals(0, dto.getErrorCode());
            assertEquals(1, dto.getSources().size());
        }

        @Test
        @DisplayName("from() should convert OpenRouterResponseDto")
        void fromShouldConvertResponseDto() {
            OpenRouterResponseDto response = new OpenRouterResponseDto(true, "msg", null, 200,
                    OpenRouterErrorCode.NONE);
            OpenRouterPublicDto dto = OpenRouterPublicDto.from(response);
            assertTrue(dto.isSuccess());
            assertEquals("msg", dto.getMessage());
            assertEquals(0, dto.getErrorCode());
        }

        @Test
        @DisplayName("from() should handle null errorCode")
        void fromShouldHandleNullErrorCode() {
            OpenRouterResponseDto response = new OpenRouterResponseDto(false, null,
                    "error", 400, null);
            OpenRouterPublicDto dto = OpenRouterPublicDto.from(response);
            assertFalse(dto.isSuccess());
            assertEquals(0, dto.getErrorCode());
        }

        @Test
        @DisplayName("from() should return null for null input")
        void fromShouldReturnNullForNullInput() {
            assertNull(OpenRouterPublicDto.from(null));
        }

        @Test
        @DisplayName("Should handle null sources")
        void shouldHandleNullSources() {
            OpenRouterPublicDto dto = new OpenRouterPublicDto(true, "msg", null, 0, null);
            assertNotNull(dto.getSources());
            assertTrue(dto.getSources().isEmpty());
        }
    }

    @Nested
    @DisplayName("ComplainantIdentity Tests")
    class ComplainantIdentityTests {
        @Test
        @DisplayName("isComplete should return true when all fields present")
        void isCompleteShouldReturnTrue() {
            ComplainantIdentity id = new ComplainantIdentity("Joan", "Garcia", "12345678Z");
            assertTrue(id.isComplete());
        }

        @Test
        @DisplayName("isComplete should return false when name is null")
        void isCompleteShouldReturnFalseWhenNameNull() {
            ComplainantIdentity id = new ComplainantIdentity(null, "Garcia", "12345678Z");
            assertFalse(id.isComplete());
        }

        @Test
        @DisplayName("isComplete should return false when surname is blank")
        void isCompleteShouldReturnFalseWhenSurnameBlank() {
            ComplainantIdentity id = new ComplainantIdentity("Joan", "  ", "12345678Z");
            assertFalse(id.isComplete());
        }

        @Test
        @DisplayName("isPartiallyProvided should return true when any field present")
        void isPartiallyProvidedShouldReturnTrue() {
            ComplainantIdentity id = new ComplainantIdentity("Joan", null, null);
            assertTrue(id.isPartiallyProvided());
        }

        @Test
        @DisplayName("isPartiallyProvided should return false when all fields null")
        void isPartiallyProvidedShouldReturnFalse() {
            ComplainantIdentity id = new ComplainantIdentity(null, null, null);
            assertFalse(id.isPartiallyProvided());
        }

        @Test
        @DisplayName("isPartiallyProvided should return false when all fields blank")
        void isPartiallyProvidedShouldReturnFalseWhenAllBlank() {
            ComplainantIdentity id = new ComplainantIdentity("", "", "");
            assertFalse(id.isPartiallyProvided());
        }
    }

    @Nested
    @DisplayName("OpenRouterErrorCode Tests")
    class OpenRouterErrorCodeTests {
        @Test
        @DisplayName("Should have expected enum constants")
        void shouldHaveExpectedConstants() {
            assertEquals(10, OpenRouterErrorCode.values().length);
        }

        @Test
        @DisplayName("Should return correct codes")
        void shouldReturnCorrectCodes() {
            assertEquals(0, OpenRouterErrorCode.NONE.getCode());
            assertEquals(1, OpenRouterErrorCode.VALIDATION.getCode());
            assertEquals(2, OpenRouterErrorCode.REFUSAL.getCode());
            assertEquals(3, OpenRouterErrorCode.UPSTREAM.getCode());
            assertEquals(4, OpenRouterErrorCode.TIMEOUT.getCode());
            assertEquals(5, OpenRouterErrorCode.INTERNAL.getCode());
            assertEquals(6, OpenRouterErrorCode.UNAUTHORIZED.getCode());
            assertEquals(7, OpenRouterErrorCode.RATE_LIMITED.getCode());
            assertEquals(8, OpenRouterErrorCode.CIRCUIT_OPEN.getCode());
            assertEquals(9, OpenRouterErrorCode.CITY_DISABLED.getCode());
        }

        @Test
        @DisplayName("toValue should return numeric code")
        void toValueShouldReturnCode() {
            assertEquals(0, OpenRouterErrorCode.NONE.toValue());
            assertEquals(5, OpenRouterErrorCode.INTERNAL.toValue());
        }

        @Test
        @DisplayName("valueOf should resolve valid constants")
        void valueOfShouldResolve() {
            assertEquals(OpenRouterErrorCode.NONE, OpenRouterErrorCode.valueOf("NONE"));
            assertEquals(OpenRouterErrorCode.RATE_LIMITED, OpenRouterErrorCode.valueOf("RATE_LIMITED"));
        }
    }

    @Nested
    @DisplayName("Source Tests")
    class SourceTests {
        @Test
        @DisplayName("Should create Source with url and title")
        void shouldCreateSource() {
            Source s = new Source("https://example.com", "Example Title");
            assertEquals("https://example.com", s.getUrl());
            assertEquals("Example Title", s.getTitle());
        }

        @Test
        @DisplayName("Should support null url")
        void shouldSupportNullUrl() {
            Source s = new Source(null, "Title");
            assertNull(s.getUrl());
            assertEquals("Title", s.getTitle());
        }
    }

    @Nested
    @DisplayName("OutputFormat Tests")
    class OutputFormatTests {
        @Test
        @DisplayName("Should have expected constants")
        void shouldHaveExpectedConstants() {
            assertEquals(3, OutputFormat.values().length);
        }

        @Test
        @DisplayName("toValue should return lowercase name")
        void toValueShouldReturnLowercase() {
            assertEquals("json", OutputFormat.JSON.toValue());
            assertEquals("pdf", OutputFormat.PDF.toValue());
            assertEquals("auto", OutputFormat.AUTO.toValue());
        }

        @Test
        @DisplayName("fromString should parse valid values")
        void fromStringShouldParseValidValues() {
            assertEquals(OutputFormat.PDF, OutputFormat.fromString("pdf"));
            assertEquals(OutputFormat.JSON, OutputFormat.fromString("json"));
            assertEquals(OutputFormat.AUTO, OutputFormat.fromString("auto"));
        }

        @Test
        @DisplayName("fromString should be case-insensitive")
        void fromStringShouldBeCaseInsensitive() {
            assertEquals(OutputFormat.PDF, OutputFormat.fromString("PDF"));
            assertEquals(OutputFormat.JSON, OutputFormat.fromString("JSON"));
            assertEquals(OutputFormat.AUTO, OutputFormat.fromString("AUTO"));
        }

        @Test
        @DisplayName("fromString should return null for unrecognised values")
        void fromStringShouldReturnNullForUnrecognised() {
            assertNull(OutputFormat.fromString("unknown"));
        }

        @Test
        @DisplayName("fromString should return PDF for null")
        void fromStringShouldReturnPdfForNull() {
            assertEquals(OutputFormat.PDF, OutputFormat.fromString(null));
        }

        @Test
        @DisplayName("isSupportedClientFormat should accept PDF and AUTO")
        void isSupportedClientFormatShouldAcceptPdfAndAuto() {
            assertTrue(OutputFormat.isSupportedClientFormat(OutputFormat.PDF));
            assertTrue(OutputFormat.isSupportedClientFormat(OutputFormat.AUTO),
                    "AUTO is accepted at the HTTP boundary and resolved to PDF by the controller");
            assertFalse(OutputFormat.isSupportedClientFormat(OutputFormat.JSON));
            assertFalse(OutputFormat.isSupportedClientFormat(null));
        }
    }

    @Nested
    @DisplayName("RedactAcceptedDto Tests")
    class RedactAcceptedDtoTests {
        @Test
        @DisplayName("Should create RedactAcceptedDto")
        void shouldCreateRedactAcceptedDto() {
            RedactAcceptedDto dto = new RedactAcceptedDto(true, "Queued", "https://example.com/pdf", 0);
            assertTrue(dto.success());
            assertEquals("Queued", dto.message());
            assertEquals("https://example.com/pdf", dto.pdfUrl());
            assertEquals(0, dto.errorCode());
        }
    }

    @Nested
    @DisplayName("RedactSqsMessage Tests")
    class RedactSqsMessageTests {
        @Test
        @DisplayName("Should create with constructor")
        void shouldCreateWithConstructor() {
            RedactSqsMessage msg = new RedactSqsMessage(
                    "complaint text", "Joan", "Garcia", "12345678Z",
                    "s3/key", "conv-1", "elprat");
            assertEquals("complaint text", msg.complaintText());
            assertEquals("Joan", msg.requesterName());
            assertEquals("Garcia", msg.requesterSurname());
            assertEquals("12345678Z", msg.requesterIdNumber());
            assertEquals("s3/key", msg.s3Key());
            assertEquals("conv-1", msg.conversationId());
            assertEquals("elprat", msg.cityId());
        }

        @Test
        @DisplayName("Should create via fromJson")
        void shouldCreateViaFromJson() {
            RedactSqsMessage msg = RedactSqsMessage.fromJson(
                    "text", "Name", "Surname", "NIF",
                    "key", "conv", "city");
            assertEquals("text", msg.complaintText());
            assertEquals("city", msg.cityId());
        }
    }

    // =========================================================================
    // Home DTOs
    // =========================================================================

    @Nested
    @DisplayName("HealthDto Tests")
    class HealthDtoTests {
        @Test
        @DisplayName("Should create HealthDto with all fields")
        void shouldCreateHealthDto() {
            HealthDto dto = new HealthDto("UP", "1.0.0", java.util.Map.of("db", "ok"));
            assertEquals("UP", dto.getStatus());
            assertEquals("1.0.0", dto.getVersion());
            assertEquals(1, dto.getChecks().size());
        }
    }

    @Nested
    @DisplayName("HomeDto Tests")
    class HomeDtoTests {
        @Test
        @DisplayName("Should create HomeDto with message")
        void shouldCreateHomeDto() {
            HomeDto dto = new HomeDto("Welcome");
            assertEquals("Welcome", dto.getMessage());
        }
    }

    // =========================================================================
    // AskRequest DTO
    // =========================================================================

    @Nested
    @DisplayName("AskRequest Tests")
    class AskRequestTests {
        @Test
        @DisplayName("Should create single-turn request")
        void shouldCreateSingleTurn() {
            AskRequest req = new AskRequest("What is the law?");
            assertEquals("What is the law?", req.getText());
            assertNull(req.getConversationId());
        }

        @Test
        @DisplayName("Should create multi-turn request")
        void shouldCreateMultiTurn() {
            AskRequest req = new AskRequest("What is the law?", "conv-123");
            assertEquals("What is the law?", req.getText());
            assertEquals("conv-123", req.getConversationId());
        }

        @Test
        @DisplayName("Should support null text")
        void shouldSupportNullText() {
            AskRequest req = new AskRequest(null);
            assertNull(req.getText());
        }
    }

    // =========================================================================
    // QuestionCategory Enum
    // =========================================================================

    @Nested
    @DisplayName("QuestionCategory Tests")
    class QuestionCategoryTests {
        @Test
        @DisplayName("Should have expected constants")
        void shouldHaveExpectedConstants() {
            assertEquals(7, QuestionCategory.values().length);
        }

        @Test
        @DisplayName("valueOf should resolve valid constants")
        void valueOfShouldResolve() {
            assertEquals(QuestionCategory.PARKING, QuestionCategory.valueOf("PARKING"));
            assertEquals(QuestionCategory.TAX, QuestionCategory.valueOf("TAX"));
            assertEquals(QuestionCategory.GARBAGE, QuestionCategory.valueOf("GARBAGE"));
            assertEquals(QuestionCategory.LIBRARY, QuestionCategory.valueOf("LIBRARY"));
            assertEquals(QuestionCategory.COMPLAINT, QuestionCategory.valueOf("COMPLAINT"));
            assertEquals(QuestionCategory.ADMINISTRATION, QuestionCategory.valueOf("ADMINISTRATION"));
            assertEquals(QuestionCategory.OTHER, QuestionCategory.valueOf("OTHER"));
        }
    }
}
