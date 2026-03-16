package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.RedactAcceptedDto;
import cat.complai.openrouter.helpers.LanguageDetector;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.controllers.dto.AskRequest;
import cat.complai.openrouter.controllers.dto.RedactRequest;
import cat.complai.openrouter.dto.ComplainantIdentity;
import cat.complai.openrouter.dto.OutputFormat;
import cat.complai.openrouter.helpers.AuditLogger;
import cat.complai.s3.S3PdfUploader;
import cat.complai.sqs.SqsComplaintPublisher;
import cat.complai.sqs.dto.RedactSqsMessage;
import cat.complai.auth.IdentityTokenValidationException;
import cat.complai.auth.JwtAuthFilter;
import cat.complai.auth.OidcIdentityTokenValidator;
import cat.complai.auth.VerifiedCitizenIdentity;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
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
    // Null when IDENTITY_VERIFICATION_ENABLED is absent/false — the bean does not load,
    // and Micronaut injects null for @Nullable constructor parameters with missing beans.
    private final OidcIdentityTokenValidator identityTokenValidator;
    private final Logger logger = Logger.getLogger(OpenRouterController.class.getName());

    @Inject
    public OpenRouterController(IOpenRouterService service,
                                SqsComplaintPublisher sqsPublisher,
                                S3PdfUploader s3PdfUploader,
                                @Nullable OidcIdentityTokenValidator identityTokenValidator) {
        this.service                  = service;
        this.sqsPublisher             = sqsPublisher;
        this.s3PdfUploader            = s3PdfUploader;
        this.identityTokenValidator   = identityTokenValidator;
    }

    @Post("/ask")
    public HttpResponse<OpenRouterPublicDto> ask(@Body AskRequest request, HttpRequest<?> httpRequest) {
        String cityId = httpRequest.getAttribute(JwtAuthFilter.CITY_ATTRIBUTE, String.class)
                .orElseThrow(() -> new IllegalStateException("city attribute missing from request — JWT filter should have set it"));
        String conversationId = request != null ? request.getConversationId() : null;
        int inputLength = request != null && request.getText() != null ? request.getText().length() : 0;
        logger.info(() -> "POST /complai/ask received — conversationId=" + conversationId + " inputLength=" + inputLength + " city=" + cityId);
        long start = System.currentTimeMillis();
        try {
            OpenRouterResponseDto dto = service.ask(request.getText(), request.getConversationId(), cityId);
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log("/complai/ask", AuditLogger.hashText(request.getText()),
                    dto != null ? dto.getErrorCode().getCode() : -1, latency, null, null);
            MutableHttpResponse<OpenRouterPublicDto> response = errorToHttpResponse(dto, "ask");
            logger.info(() -> "POST /complai/ask completed — httpStatus=" + response.status().getCode()
                    + " errorCode=" + (dto != null ? dto.getErrorCode() : "null")
                    + " latencyMs=" + latency + " conversationId=" + conversationId);
            return response;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log("/complai/ask",
                    AuditLogger.hashText(request != null ? request.getText() : null),
                    OpenRouterErrorCode.INTERNAL.getCode(), latency, null, null);
            logger.log(Level.SEVERE, "POST /complai/ask failed — httpStatus=500"
                    + " latencyMs=" + latency + " conversationId=" + conversationId, e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null, e.getMessage(), OpenRouterErrorCode.INTERNAL.getCode());
            return HttpResponse.serverError(err);
        }
    }

    @Post("/redact")
    @Produces({MediaType.APPLICATION_PDF, MediaType.APPLICATION_JSON})
    public HttpResponse<?> redact(@Body RedactRequest request, HttpRequest<?> httpRequest) {
        String cityId = httpRequest.getAttribute(JwtAuthFilter.CITY_ATTRIBUTE, String.class)
                .orElseThrow(() -> new IllegalStateException("city attribute missing from request — JWT filter should have set it"));
        String conversationId = request != null ? request.getConversationId() : null;
        int inputLength = request != null && request.getText() != null ? request.getText().length() : 0;
        OutputFormat requestedFormat = request != null ? request.getFormat() : null;
        logger.info(() -> "POST /complai/redact received — conversationId=" + conversationId
                + " inputLength=" + inputLength + " format=" + requestedFormat + " city=" + cityId);
        long start = System.currentTimeMillis();
        try {
            String text           = request != null ? request.getText() : null;
            OutputFormat format   = request == null ? OutputFormat.AUTO : request.getFormat();
            ComplainantIdentity identity = request != null ? request.getComplainantIdentity() : null;

            if (!OutputFormat.isSupportedClientFormat(format)) {
                long latency = System.currentTimeMillis() - start;
                AuditLogger.log("/complai/redact", AuditLogger.hashText(text),
                        OpenRouterErrorCode.VALIDATION.getCode(), latency, format != null ? format.name() : null, null);
                logger.info(() -> "POST /complai/redact rejected — httpStatus=400 reason=unsupportedFormat"
                        + " format=" + format + " latencyMs=" + latency + " conversationId=" + conversationId);
                OpenRouterPublicDto err = new OpenRouterPublicDto(false, null,
                        "Unsupported format. Only 'pdf', 'json', or 'auto' are accepted. Documents are always produced as PDF.",
                        OpenRouterErrorCode.VALIDATION.getCode());
                return HttpResponse.badRequest(err).contentType(MediaType.APPLICATION_JSON);
            }

            // When IDENTITY_VERIFICATION_ENABLED=true the validator bean is present.
            // If the caller includes an X-Identity-Token header, validate it and use the
            // IdP-verified identity instead of the self-reported body fields. This prevents
            // the AI from asking for missing identity in a multi-turn loop, and ensures the
            // PDF contains a cryptographically-verified NIF/NIE rather than user-supplied data.
            //
            // If the header is present but the feature is disabled (validator == null), we
            // ignore it silently — this allows the frontend to roll out before the backend
            // flag is enabled without causing errors.
            if (identityTokenValidator != null) {
                String rawIdentityToken = httpRequest.getHeaders().get("X-Identity-Token");
                if (rawIdentityToken != null && !rawIdentityToken.isBlank()) {
                    try {
                        VerifiedCitizenIdentity verified = identityTokenValidator.validate(rawIdentityToken);
                        identity = new ComplainantIdentity(
                                verified.name(), verified.surname(), verified.nif());
                    } catch (IdentityTokenValidationException e) {
                        long latency = System.currentTimeMillis() - start;
                        AuditLogger.log("/complai/redact", AuditLogger.hashText(text),
                                OpenRouterErrorCode.UNAUTHORIZED.getCode(), latency,
                                format != null ? format.name() : null, null);
                        logger.warning(() -> "POST /complai/redact rejected — httpStatus=401"
                                + " reason=invalidIdentityToken: " + e.getMessage()
                                + " conversationId=" + conversationId);
                        OpenRouterPublicDto err = new OpenRouterPublicDto(false, null,
                                "Identity token is invalid. Please re-authenticate.",
                                OpenRouterErrorCode.UNAUTHORIZED.getCode());
                        return HttpResponse.unauthorized().body(err)
                                .contentType(MediaType.APPLICATION_JSON);
                    }
                }
            }

            boolean identityComplete = identity != null && identity.isComplete();

            if (identityComplete && format != OutputFormat.JSON) {
                return handleAsyncRedact(text, format, conversationId, identity, cityId, start);
            }

            OpenRouterResponseDto dto = service.redactComplaint(text, format, conversationId, identity, cityId);
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log("/complai/redact", AuditLogger.hashText(text),
                    dto != null ? dto.getErrorCode().getCode() : -1, latency,
                    format != null ? format.name() : null, null);

            HttpResponse<?> response = errorToHttpResponse(dto, "redact");
            logger.info(() -> "POST /complai/redact completed (sync) — httpStatus="
                    + (response instanceof MutableHttpResponse<?> mr ? mr.status().getCode() : "?")
                    + " errorCode=" + (dto != null ? dto.getErrorCode() : "null")
                    + " latencyMs=" + latency + " conversationId=" + conversationId
                    + " identityComplete=" + identityComplete);
            return response;

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log("/complai/redact",
                    AuditLogger.hashText(request != null ? request.getText() : null),
                    OpenRouterErrorCode.INTERNAL.getCode(), latency,
                    request != null && request.getFormat() != null ? request.getFormat().name() : null, null);
            logger.log(Level.SEVERE, "POST /complai/redact failed — httpStatus=500"
                    + " latencyMs=" + latency + " conversationId=" + conversationId, e);
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
                                              String cityId, long requestStart) {
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
            logger.log(Level.SEVERE, "POST /complai/redact — httpStatus=500 reason=presignedUrlFailed"
                    + " s3Key=" + s3Key + " conversationId=" + conversationId, e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null,
                    "Failed to prepare complaint storage URL.", OpenRouterErrorCode.INTERNAL.getCode());
            return HttpResponse.serverError(err).contentType(MediaType.APPLICATION_JSON);
        }

        RedactSqsMessage sqsMessage = new RedactSqsMessage(
                text, identity.name(), identity.surname(), identity.idNumber(),
                s3Key, conversationId, cityId);
        try {
            sqsPublisher.publish(sqsMessage);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "POST /complai/redact — httpStatus=500 reason=sqsPublishFailed"
                    + " s3Key=" + s3Key + " conversationId=" + conversationId, e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null,
                    "Failed to queue complaint generation. Please try again.",
                    OpenRouterErrorCode.INTERNAL.getCode());
            return HttpResponse.serverError(err).contentType(MediaType.APPLICATION_JSON);
        }

        long latency = System.currentTimeMillis() - requestStart;
        String detectedLanguage = LanguageDetector.detect(text);
        AuditLogger.log("/complai/redact", AuditLogger.hashText(text),
                OpenRouterErrorCode.NONE.getCode(), latency, format != null ? format.name() : null, detectedLanguage);

        logger.info(() -> "POST /complai/redact completed (async) — httpStatus=202"
                + " s3Key=" + s3Key + " latencyMs=" + latency
                + " conversationId=" + conversationId + " language=" + detectedLanguage);

        String acceptedMessage = switch (detectedLanguage) {
            case "CA" -> "La vostra carta de reclamació s'està generant. Estarà disponible d'aquí a pocs minuts a l'adreça de sota.";
            case "ES" -> "Su carta de queja se está generando. Estará disponible en breve en la dirección siguiente.";
            default   -> "Your complaint letter is being created. It will be available shortly at the URL below.";
        };

        RedactAcceptedDto accepted = new RedactAcceptedDto(
                true,
                acceptedMessage,
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
                logger.fine(() -> operation + ": httpStatus=400 errorCode=VALIDATION — " + dto.getError());
                yield HttpResponse.badRequest(publicDto);
            }
            case REFUSAL -> {
                logger.info(() -> operation + ": httpStatus=422 errorCode=REFUSAL — request out of scope for El Prat");
                yield HttpResponse.status(HttpStatus.UNPROCESSABLE_ENTITY).body(publicDto);
            }
            case TIMEOUT -> {
                logger.warning(() -> operation + ": httpStatus=504 errorCode=TIMEOUT — AI service timed out");
                yield HttpResponse.status(HttpStatus.GATEWAY_TIMEOUT).body(publicDto);
            }
            case UPSTREAM -> {
                logger.warning(() -> operation + ": httpStatus=502 errorCode=UPSTREAM — " + dto.getError());
                yield HttpResponse.status(HttpStatus.BAD_GATEWAY).body(publicDto);
            }
            default -> {
                logger.warning(() -> operation + ": httpStatus=500 errorCode=" + errorCode
                        + " — " + (dto != null ? dto.getError() : "null dto"));
                yield HttpResponse.serverError(publicDto);
            }
        };

        return response.contentType(MediaType.APPLICATION_JSON);
    }
}
