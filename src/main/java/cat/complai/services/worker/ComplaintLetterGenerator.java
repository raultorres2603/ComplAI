package cat.complai.services.worker;

import cat.complai.utilities.http.HttpWrapper;
import cat.complai.dto.http.HttpDto;
import cat.complai.dto.openrouter.ComplainantIdentity;
import cat.complai.helpers.openrouter.AiParsed;
import cat.complai.helpers.openrouter.PdfGenerator;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.utilities.s3.S3PdfUploader;
import cat.complai.dto.sqs.RedactSqsMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Standalone (non-Micronaut) class that processes a single complaint-letter generation job.
 *
 * <p>It is extracted from {@link RedactWorkerHandler} so that unit tests can instantiate and
 * exercise the core logic directly — without triggering the {@code MicronautRequestHandler}
 * constructor which initialises an {@code ApplicationContext} and attempts to bind an HTTP port.
 *
 * <p>{@link RedactWorkerHandler} constructs this class from its DI-managed fields and delegates
 * all record processing to it. The separation keeps DI wiring in the handler and business logic
 * here.
 */
class ComplaintLetterGenerator {

    private static final Logger logger = Logger.getLogger(ComplaintLetterGenerator.class.getName());

    private final RedactPromptBuilder promptBuilder;
    private final HttpWrapper httpWrapper;
    private final S3PdfUploader s3PdfUploader;
    private final int overallTimeoutSeconds;

    ComplaintLetterGenerator(RedactPromptBuilder promptBuilder,
                              HttpWrapper httpWrapper,
                              S3PdfUploader s3PdfUploader,
                              int overallTimeoutSeconds) {
        this.promptBuilder        = promptBuilder;
        this.httpWrapper          = httpWrapper;
        this.s3PdfUploader        = s3PdfUploader;
        this.overallTimeoutSeconds = overallTimeoutSeconds;
    }

    /**
     * Calls the AI, generates the PDF, and uploads it to S3 at the key specified in the message.
     *
     * @throws Exception on any failure — the caller reports this as a batch item failure so SQS
     *                   retries the message
     */
    void generate(RedactSqsMessage message) throws Exception {
        long start = System.currentTimeMillis();
        logger.info(() -> "ComplaintLetterGenerator — starting s3Key=" + message.s3Key()
                + " conversationId=" + message.conversationId()
                + " complaintLength=" + (message.complaintText() != null ? message.complaintText().length() : 0));

        ComplainantIdentity identity = new ComplainantIdentity(
                message.requesterName(), message.requesterSurname(), message.requesterIdNumber());

        // cityId is required — the API Lambda must set it when publishing the SQS message.
        // Failing here ensures a missing cityId surfaces immediately rather than silently
        // using the wrong procedures context.
        String cityId = message.cityId();
        if (cityId == null || cityId.isBlank()) {
            throw new IllegalArgumentException("RedactSqsMessage is missing cityId — cannot determine procedures context");
        }

        List<Map<String, Object>> aiMessages = buildAiMessages(message.complaintText(), identity, cityId);
        logger.fine(() -> "ComplaintLetterGenerator — AI prompt built messageCount=" + aiMessages.size()
                + " s3Key=" + message.s3Key());

        HttpDto aiResult = httpWrapper.postToOpenRouterAsync(aiMessages)
                .get(overallTimeoutSeconds, TimeUnit.SECONDS);

        logger.info(() -> "ComplaintLetterGenerator — AI responded httpStatus=" + (aiResult != null ? aiResult.statusCode() : "null")
                + " hasMessage=" + (aiResult != null && aiResult.message() != null && !aiResult.message().isBlank())
                + " hasError=" + (aiResult != null && aiResult.error() != null && !aiResult.error().isBlank())
                + " s3Key=" + message.s3Key());

        validateAiResult(aiResult, message.s3Key());

        String letterBody = extractLetterBody(aiResult.message(), message.s3Key());
        byte[] pdfBytes   = PdfGenerator.generatePdf(letterBody);

        logger.fine(() -> "ComplaintLetterGenerator — PDF generated pdfSizeBytes=" + pdfBytes.length
                + " letterBodyLength=" + letterBody.length() + " s3Key=" + message.s3Key());

        s3PdfUploader.upload(message.s3Key(), pdfBytes);

        long latencyMs = System.currentTimeMillis() - start;
        logger.info(() -> "ComplaintLetterGenerator — completed successfully s3Key=" + message.s3Key()
                + " pdfSizeBytes=" + pdfBytes.length + " totalLatencyMs=" + latencyMs);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> buildAiMessages(String complaintText, ComplainantIdentity identity, String cityId) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", promptBuilder.getSystemMessage(cityId)));

        String contextBlock = promptBuilder.buildProcedureContextBlock(complaintText, cityId);
        if (contextBlock != null) {
            messages.add(Map.of("role", "system", "content", contextBlock));
        }

        String userPrompt = promptBuilder.buildRedactPromptWithIdentity(complaintText, identity, cityId);
        messages.add(Map.of("role", "user", "content", userPrompt));
        return messages;
    }

    private static void validateAiResult(HttpDto result, String s3Key) {
        if (result == null) {
            throw new RuntimeException("AI service returned null response for s3Key=" + s3Key);
        }
        if (result.error() != null && !result.error().isBlank()) {
            throw new RuntimeException("AI service error for s3Key=" + s3Key + ": " + result.error());
        }
        if (result.message() == null || result.message().isBlank()) {
            throw new RuntimeException("AI returned an empty message for s3Key=" + s3Key);
        }
    }

    /**
     * Strips the optional JSON format header from the AI message and returns the letter body.
     * Falls back to the full AI message when no header is present.
     */
    private static String extractLetterBody(String aiMessage, String s3Key) {
        AiParsed parsed = AiParsed.parseAiFormatHeader(aiMessage);
        String body = parsed.message();
        if (body == null || body.isBlank()) {
            logger.warning("AI returned empty letter body after header parsing for s3Key=" + s3Key
                    + "; using raw AI message as fallback.");
            body = aiMessage;
        }
        return body;
    }
}

