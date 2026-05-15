package cat.complai.services.worker;

import cat.complai.dto.http.HttpDto;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.dto.sqs.RedactSqsMessage;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.utilities.http.HttpWrapper;
import cat.complai.utilities.s3.S3PdfUploader;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplaintLetterGeneratorTest {

    @Mock
    private RedactPromptBuilder promptBuilder;

    @Mock
    private HttpWrapper httpWrapper;

    @Mock
    private S3PdfUploader s3PdfUploader;

    @Captor
    private ArgumentCaptor<byte[]> pdfBytesCaptor;

    private ComplaintLetterGenerator generator;

    private static final int TIMEOUT = 30;
    private static final String CITY_ID = "elprat";
    private static final String S3_KEY = "complaints/test/letter.pdf";
    private static final String COMPLAINT_TEXT = "Noise from the airport";
    private static final String NAME = "Joan";
    private static final String SURNAME = "Garcia";
    private static final String ID_NUMBER = "12345678A";
    private static final String CONVERSATION_ID = "conv-001";

    @BeforeEach
    void setUp() {
        generator = new ComplaintLetterGenerator(promptBuilder, httpWrapper, s3PdfUploader, TIMEOUT);
    }

    private RedactSqsMessage message(String cityId) {
        return new RedactSqsMessage(COMPLAINT_TEXT, NAME, SURNAME, ID_NUMBER,
                S3_KEY, CONVERSATION_ID, cityId);
    }

    private void stubPromptBuilder() {
        when(promptBuilder.getSystemMessage(anyString())).thenReturn("system-msg");
        when(promptBuilder.buildProcedureContextBlock(anyString(), anyString())).thenReturn("context-block");
        when(promptBuilder.buildRedactPromptWithIdentity(anyString(), any(), anyString()))
                .thenReturn("user-prompt");
    }

    private void stubHttpWrapper(HttpDto result) {
        when(httpWrapper.postToOpenRouterAsync(anyList()))
                .thenReturn(CompletableFuture.completedFuture(result));
    }

    @Test
    void generate_successfulFlow_uploadsPdf() throws Exception {
        String rawAiMessage = "{\"format\": \"pdf\"}\n\nDear Ajuntament,\n\nI am writing to complain.\n\nSincerely,\nJoan Garcia";
        stubPromptBuilder();
        stubHttpWrapper(new HttpDto(rawAiMessage, 200, "POST", null));

        generator.generate(message(CITY_ID));

        verify(promptBuilder).getSystemMessage(CITY_ID);
        verify(promptBuilder).buildProcedureContextBlock(COMPLAINT_TEXT, CITY_ID);
        verify(promptBuilder).buildRedactPromptWithIdentity(
                eq(COMPLAINT_TEXT), any(ComplainantIdentity.class), eq(CITY_ID));
        verify(httpWrapper).postToOpenRouterAsync(anyList());
        verify(s3PdfUploader).upload(eq(S3_KEY), pdfBytesCaptor.capture());

        byte[] pdf = pdfBytesCaptor.getValue();
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertEquals('%', pdf[0]);
        assertEquals('P', pdf[1]);
        assertEquals('D', pdf[2]);
        assertEquals('F', pdf[3]);
    }

    @Test
    void generate_nullAiResult_throwsRuntimeException() {
        stubPromptBuilder();
        stubHttpWrapper(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> generator.generate(message(CITY_ID)));
        assertTrue(ex.getMessage().contains("null response"));
        assertTrue(ex.getMessage().contains(S3_KEY));
        verify(s3PdfUploader, never()).upload(anyString(), any(byte[].class));
    }

    @Test
    void generate_aiResultWithError_throwsRuntimeException() {
        stubPromptBuilder();
        stubHttpWrapper(new HttpDto(null, 500, "POST", "Upstream API error"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> generator.generate(message(CITY_ID)));
        assertTrue(ex.getMessage().contains("AI service error"));
        assertTrue(ex.getMessage().contains(S3_KEY));
        assertTrue(ex.getMessage().contains("Upstream API error"));
        verify(s3PdfUploader, never()).upload(anyString(), any(byte[].class));
    }

    @Test
    void generate_aiResultNullMessage_throwsRuntimeException() {
        stubPromptBuilder();
        stubHttpWrapper(new HttpDto(null, 200, "POST", null));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> generator.generate(message(CITY_ID)));
        assertTrue(ex.getMessage().contains("empty message"));
        assertTrue(ex.getMessage().contains(S3_KEY));
        verify(s3PdfUploader, never()).upload(anyString(), any(byte[].class));
    }

    @Test
    void generate_aiResultBlankMessage_throwsRuntimeException() {
        stubPromptBuilder();
        stubHttpWrapper(new HttpDto("   ", 200, "POST", null));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> generator.generate(message(CITY_ID)));
        assertTrue(ex.getMessage().contains("empty message"));
        verify(s3PdfUploader, never()).upload(anyString(), any(byte[].class));
    }

    @Test
    void generate_nullCityId_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(message(null)));
        verifyNoInteractions(promptBuilder, httpWrapper, s3PdfUploader);
    }

    @Test
    void generate_blankCityId_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> generator.generate(message("   ")));
        verifyNoInteractions(promptBuilder, httpWrapper, s3PdfUploader);
    }

    @Test
    void generate_aiResponseWithJsonHeader_extractsBodyForPdf() throws Exception {
        String letterBody = "Dear Mayor,\n\nI wish to file a complaint.\n\nSincerely,\nTest User";
        stubPromptBuilder();
        stubHttpWrapper(new HttpDto("{\"format\": \"pdf\"}\n\n" + letterBody, 200, "POST", null));

        generator.generate(message(CITY_ID));

        verify(s3PdfUploader).upload(eq(S3_KEY), pdfBytesCaptor.capture());
        byte[] pdf = pdfBytesCaptor.getValue();
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertEquals('%', pdf[0]);
        assertEquals('P', pdf[1]);
        assertEquals('D', pdf[2]);
        assertEquals('F', pdf[3]);
    }

    @Test
    void generate_aiResponseEmptyBodyAfterHeaderParsing_fallsBackToRawMessage() throws Exception {
        stubPromptBuilder();
        stubHttpWrapper(new HttpDto("{\"format\": \"pdf\"}", 200, "POST", null));

        generator.generate(message(CITY_ID));

        verify(s3PdfUploader).upload(eq(S3_KEY), pdfBytesCaptor.capture());
        byte[] pdf = pdfBytesCaptor.getValue();
        assertNotNull(pdf);
        assertTrue(pdf.length > 0);
        assertEquals('%', pdf[0]);
        assertEquals('P', pdf[1]);
        assertEquals('D', pdf[2]);
        assertEquals('F', pdf[3]);
    }
}
