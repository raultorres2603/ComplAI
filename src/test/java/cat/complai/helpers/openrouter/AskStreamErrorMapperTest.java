package cat.complai.helpers.openrouter;

import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.sse.SseErrorEvent;
import cat.complai.exceptions.OpenRouterStreamingException;

import org.junit.jupiter.api.Test;

import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class AskStreamErrorMapperTest {

    // -------------------------------------------------------------------------
    // toResponseDto — error code mapping
    // -------------------------------------------------------------------------

    @Test
    void toResponseDto_timeoutException_returnsTimeoutErrorCode() {
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(new TimeoutException("timed out"));

        assertFalse(dto.isSuccess());
        assertNull(dto.getMessage());
        assertEquals("AI service timed out.", dto.getError());
        assertNull(dto.getStatusCode());
        assertEquals(OpenRouterErrorCode.TIMEOUT, dto.getErrorCode());
    }

    @Test
    void toResponseDto_httpTimeoutException_returnsTimeoutErrorCode() {
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(new HttpTimeoutException("http timed out"));

        assertFalse(dto.isSuccess());
        assertEquals("AI service timed out.", dto.getError());
        assertEquals(OpenRouterErrorCode.TIMEOUT, dto.getErrorCode());
    }

    @Test
    void toResponseDto_openRouterStreamingUpstream_returnsUpstreamErrorCode() {
        OpenRouterStreamingException ex = new OpenRouterStreamingException(
                OpenRouterErrorCode.UPSTREAM, "upstream error", 503);
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(ex);

        assertFalse(dto.isSuccess());
        assertEquals("AI service is temporarily unavailable. Please try again later.", dto.getError());
        assertEquals(Integer.valueOf(503), dto.getStatusCode());
        assertEquals(OpenRouterErrorCode.UPSTREAM, dto.getErrorCode());
    }

    @Test
    void toResponseDto_openRouterStreamingValidation_returnsValidationErrorCode() {
        OpenRouterStreamingException ex = new OpenRouterStreamingException(
                OpenRouterErrorCode.VALIDATION, "custom validation message", null);
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(ex);

        assertFalse(dto.isSuccess());
        assertEquals("custom validation message", dto.getError());
        assertNull(dto.getStatusCode());
        assertEquals(OpenRouterErrorCode.VALIDATION, dto.getErrorCode());
    }

    @Test
    void toResponseDto_nullPointer_returnsInternalErrorCode() {
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(new NullPointerException());

        assertEquals(OpenRouterErrorCode.INTERNAL, dto.getErrorCode());
        assertEquals("An internal error occurred. Please try again later.", dto.getError());
    }

    @Test
    void toResponseDto_validationInMessage_returnsValidationErrorCode() {
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(
                new RuntimeException("this is a validation error"));

        assertEquals(OpenRouterErrorCode.VALIDATION, dto.getErrorCode());
        assertEquals("this is a validation error", dto.getError());
    }

    @Test
    void toResponseDto_malformedUpstreamStreamInMessage_returnsUpstreamErrorCode() {
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(
                new RuntimeException("malformed upstream stream received"));

        assertEquals(OpenRouterErrorCode.UPSTREAM, dto.getErrorCode());
    }

    @Test
    void toResponseDto_openrouterInMessage_returnsUpstreamErrorCode() {
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(
                new RuntimeException("openrouter error"));

        assertEquals(OpenRouterErrorCode.UPSTREAM, dto.getErrorCode());
    }

    @Test
    void toResponseDto_upstreamInMessage_returnsUpstreamErrorCode() {
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(
                new RuntimeException("upstream connection failed"));

        assertEquals(OpenRouterErrorCode.UPSTREAM, dto.getErrorCode());
    }

    @Test
    void toResponseDto_genericException_returnsInternalErrorCode() {
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(
                new RuntimeException("something went wrong"));

        assertEquals(OpenRouterErrorCode.INTERNAL, dto.getErrorCode());
        assertEquals("An internal error occurred. Please try again later.", dto.getError());
    }

    // -------------------------------------------------------------------------
    // toResponseDto — exception unwrapping
    // -------------------------------------------------------------------------

    @Test
    void toResponseDto_openRouterStreamingExceptionWithinWrapper_unwrapsCorrectly() {
        OpenRouterStreamingException inner = new OpenRouterStreamingException(
                OpenRouterErrorCode.UPSTREAM, "inner upstream", 502);
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(
                new RuntimeException("wrapper", inner));

        assertEquals(OpenRouterErrorCode.UPSTREAM, dto.getErrorCode());
        assertEquals(Integer.valueOf(502), dto.getStatusCode());
    }

    @Test
    void toResponseDto_wrappedExceptionWithValidationMessage_returnsValidation() {
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(
                new RuntimeException("outer", new RuntimeException("this is a validation error")));

        assertEquals(OpenRouterErrorCode.VALIDATION, dto.getErrorCode());
    }

    @Test
    void toResponseDto_nullError_returnsInternalErrorCode() {
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(null);

        assertFalse(dto.isSuccess());
        assertEquals(OpenRouterErrorCode.INTERNAL, dto.getErrorCode());
        assertEquals("An internal error occurred. Please try again later.", dto.getError());
    }

    @Test
    void toResponseDto_errorWithNullMessage_returnsInternalErrorCode() {
        OpenRouterResponseDto dto = AskStreamErrorMapper.toResponseDto(
                new RuntimeException((String) null));

        assertEquals(OpenRouterErrorCode.INTERNAL, dto.getErrorCode());
    }

    // -------------------------------------------------------------------------
    // toSseErrorEvent
    // -------------------------------------------------------------------------

    @Test
    void toSseErrorEvent_timeoutException_returnsSseErrorEventWithTimeout() {
        SseErrorEvent event = AskStreamErrorMapper.toSseErrorEvent(new TimeoutException("timed out"));

        assertEquals("error", event.type());
        assertEquals("AI service timed out.", event.error());
        assertEquals(Integer.valueOf(OpenRouterErrorCode.TIMEOUT.getCode()), event.errorCode());
    }

    @Test
    void toSseErrorEvent_regularException_returnsSseErrorEvent() {
        SseErrorEvent event = AskStreamErrorMapper.toSseErrorEvent(
                new RuntimeException("something went wrong"));

        assertEquals("error", event.type());
        assertEquals("An internal error occurred. Please try again later.", event.error());
        assertEquals(Integer.valueOf(OpenRouterErrorCode.INTERNAL.getCode()), event.errorCode());
    }

    @Test
    void toSseErrorEvent_validationException_usesExceptionMessage() {
        SseErrorEvent event = AskStreamErrorMapper.toSseErrorEvent(
                new RuntimeException("validation failed"));

        assertEquals("error", event.type());
        assertEquals("validation failed", event.error());
        assertEquals(Integer.valueOf(OpenRouterErrorCode.VALIDATION.getCode()), event.errorCode());
    }

    @Test
    void toSseErrorEvent_nullError_returnsSseErrorEvent() {
        SseErrorEvent event = AskStreamErrorMapper.toSseErrorEvent(null);

        assertEquals("error", event.type());
        assertEquals("An internal error occurred. Please try again later.", event.error());
        assertEquals(Integer.valueOf(OpenRouterErrorCode.INTERNAL.getCode()), event.errorCode());
    }
}
