package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.RedactAcceptedDto;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.controllers.dto.AskRequest;
import cat.complai.openrouter.controllers.dto.RedactRequest;
import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OutputFormat;
import cat.complai.openrouter.helpers.AuditLogger;
import cat.complai.s3.S3PdfUploader;
import cat.complai.sqs.SqsComplaintPublisher;
import cat.complai.sqs.dto.RedactSqsMessage;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

@Controller("/complai")
public class OpenRouterController {

    private final IOpenRouterService service;
    private final SqsComplaintPublisher sqsPublisher;
    private final S3PdfUploader s3PdfUploader;
    private final Logger logger = Logger.getLogger(OpenRouterController.class.getName());

    @Inject
    public OpenRouterController(IOpenRouterService service,
                                SqsComplaintPublisher sqsPublisher,
                                S3PdfUploader s3PdfUploader) {
        this.service       = service;
        this.sqsPublisher  = sqsPublisher;
        this.s3PdfUploader = s3PdfUploader;
    }

    @Post("/ask")
    public HttpResponse<OpenRouterPublicDto> ask(@Body AskRequest request) {
        logger.info("POST /openrouter/ask called");
        long start = System.currentTimeMillis();
        try {
            OpenRouterResponseDto dto = service.ask(request.getText(), request.getConversationId());
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log("/complai/ask", AuditLogger.hashText(request.getText()),
                    dto != null ? dto.getErrorCode().getCode() : -1, latency, null, null);
            return errorToHttpResponse(dto, "ask");
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log("/complai/ask",
                    AuditLogger.hashText(request != null ? request.getText() : null),
                    OpenRouterErrorCode.INTERNAL.getCode(), latency, null, null);
            logger.log(Level.SEVERE, "ask: unexpected exception", e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null, e.getMessage(), OpenRouterErrorCode.INTERNAL.getCode());
            return HttpResponse.serverError(err);
        }
    }

    @Post("/redact")
    @Produces({MediaType.APPLICATION_PDF, MediaType.APPLICATION_JSON})
    public HttpResponse<?> redact(@Body RedactRequest request) {
        logger.info("POST /openrouter/redact called");
        long start = System.currentTimeMillis();
        try {
            String text           = request != null ? request.getText() : null;
            OutputFormat format   = request == null ? OutputFormat.AUTO : request.getFormat();
            String conversationId = request == null ? null : request.getConversationId();
            ComplainantIdentity identity = request != null ? request.getComplainantIdentity() : null;

            if (!OutputFormat.isSupportedClientFormat(format)) {
                long latency = System.currentTimeMillis() - start;
                AuditLogger.log("/complai/redact", AuditLogger.hashText(text),
                        OpenRouterErrorCode.VALIDATION.getCode(), latency, format != null ? format.name() : null, null);
                OpenRouterPublicDto err = new OpenRouterPublicDto(false, null,
                        "Unsupported format. Only 'pdf', 'json', or 'auto' are accepted. Documents are always produced as PDF.",
                        OpenRouterErrorCode.VALIDATION.getCode());
                return HttpResponse.badRequest(err).contentType(MediaType.APPLICATION_JSON);
            }

            boolean identityComplete = identity != null && identity.isComplete();

            // Async path: identity is known and the caller wants a PDF (or will accept one).
            // We validate, enqueue, and return 202 immediately — the worker Lambda generates
            // the PDF and uploads it to S3.
            // JSON-format requests always stay synchronous: the caller wants inline text.
            if (identityComplete && format != OutputFormat.JSON) {
                return handleAsyncRedact(text, format, conversationId, identity, start);
            }

            // Synchronous path: identity incomplete (AI will ask) or caller wants JSON text.
            OpenRouterResponseDto dto = service.redactComplaint(text, format, conversationId, identity);
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log("/complai/redact", AuditLogger.hashText(text),
                    dto != null ? dto.getErrorCode().getCode() : -1, latency,
                    format != null ? format.name() : null, null);

            return errorToHttpResponse(dto, "redact");

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log("/complai/redact",
                    AuditLogger.hashText(request != null ? request.getText() : null),
                    OpenRouterErrorCode.INTERNAL.getCode(), latency,
                    request != null && request.getFormat() != null ? request.getFormat().name() : null, null);
            logger.log(Level.SEVERE, "redact: unexpected exception", e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null, e.getMessage(), OpenRouterErrorCode.INTERNAL.getCode());
            return HttpResponse.serverError(err).contentType(MediaType.APPLICATION_JSON);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Validates, enqueues, and returns a {@code 202 Accepted} response containing the
     * pre-signed S3 URL where the worker Lambda will upload the finished PDF.
     */
    private HttpResponse<?> handleAsyncRedact(String text, OutputFormat format,
                                              String conversationId, ComplainantIdentity identity,
                                              long requestStart) {
        // Run the same validation the sync path does (input length, anonymity).
        Optional<OpenRouterResponseDto> validationError = service.validateRedactInput(text);
        if (validationError.isPresent()) {
            OpenRouterResponseDto err = validationError.get();
            long latency = System.currentTimeMillis() - requestStart;
            AuditLogger.log("/complai/redact", AuditLogger.hashText(text),
                    err.getErrorCode().getCode(), latency, format != null ? format.name() : null, null);
            return errorToHttpResponse(err, "redact").contentType(MediaType.APPLICATION_JSON);
        }

        String s3Key = buildS3Key(conversationId);
        String pdfUrl;
        try {
            pdfUrl = s3PdfUploader.generatePresignedGetUrl(s3Key);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "redact: failed to generate pre-signed S3 URL", e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null,
                    "Failed to prepare complaint storage URL.", OpenRouterErrorCode.INTERNAL.getCode());
            return HttpResponse.serverError(err).contentType(MediaType.APPLICATION_JSON);
        }

        RedactSqsMessage sqsMessage = new RedactSqsMessage(
                text, identity.name(), identity.surname(), identity.idNumber(),
                s3Key, conversationId);
        try {
            sqsPublisher.publish(sqsMessage);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "redact: failed to publish to SQS", e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null,
                    "Failed to queue complaint generation. Please try again.",
                    OpenRouterErrorCode.INTERNAL.getCode());
            return HttpResponse.serverError(err).contentType(MediaType.APPLICATION_JSON);
        }

        long latency = System.currentTimeMillis() - requestStart;
        AuditLogger.log("/complai/redact", AuditLogger.hashText(text),
                OpenRouterErrorCode.NONE.getCode(), latency, format != null ? format.name() : null, null);

        RedactAcceptedDto accepted = new RedactAcceptedDto(
                true,
                "Your complaint letter is being created. It will be available shortly at the URL below.",
                pdfUrl,
                OpenRouterErrorCode.NONE.getCode());
        return HttpResponse.status(HttpStatus.ACCEPTED).body(accepted).contentType(MediaType.APPLICATION_JSON);
    }

    /**
     * Generates the S3 object key for a complaint PDF.
     * Format: {@code complaints/<id>/<epoch-seconds>-complaint.pdf}
     *
     * <p>The folder prefix is the conversationId when provided (groups multi-turn complaints)
     * or a fresh UUID otherwise. The timestamp prefix inside the folder enables chronological
     * listing.
     */
    private static String buildS3Key(String conversationId) {
        String folder = (conversationId != null && !conversationId.isBlank())
                ? conversationId.replaceAll("[^a-zA-Z0-9\\-_.]", "-")
                : UUID.randomUUID().toString();
        long ts = Instant.now().getEpochSecond();
        return "complaints/" + folder + "/" + ts + "-complaint.pdf";
    }

    /**
     * Maps a service response to the appropriate HTTP status. The errorCode on the DTO is the
     * authoritative signal; the error message is only consulted as a fallback for legacy responses
     * that predate the errorCode field.
     */
    private MutableHttpResponse<OpenRouterPublicDto> errorToHttpResponse(OpenRouterResponseDto dto, String operation) {
        OpenRouterPublicDto publicDto = OpenRouterPublicDto.from(dto);

        if (dto != null && dto.isSuccess()) {
            return HttpResponse.ok(publicDto).contentType(MediaType.APPLICATION_JSON);
        }

        OpenRouterErrorCode errorCode = dto != null ? dto.getErrorCode() : OpenRouterErrorCode.INTERNAL;

        MutableHttpResponse<OpenRouterPublicDto> response = switch (errorCode) {
            case VALIDATION -> {
                logger.fine(operation + ": bad request - " + dto.getError());
                yield HttpResponse.badRequest(publicDto);
            }
            case REFUSAL -> {
                logger.info(operation + ": request out of scope for El Prat");
                yield HttpResponse.status(HttpStatus.UNPROCESSABLE_ENTITY).body(publicDto);
            }
            case TIMEOUT -> {
                logger.warning(operation + ": AI service timed out");
                yield HttpResponse.status(HttpStatus.GATEWAY_TIMEOUT).body(publicDto);
            }
            case UPSTREAM -> {
                logger.log(Level.WARNING, "{0}: upstream AI error: {1}", new Object[]{operation, dto.getError()});
                yield HttpResponse.status(HttpStatus.BAD_GATEWAY).body(publicDto);
            }
            default -> {
                logger.log(Level.WARNING, "{0}: unexpected error: {1}", new Object[]{operation, dto != null ? dto.getError() : "null dto"});
                yield HttpResponse.serverError(publicDto);
            }
        };

        return response.contentType(MediaType.APPLICATION_JSON);
    }
}
