package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.AskStreamResult;
import cat.complai.openrouter.dto.RedactAcceptedDto;
import cat.complai.openrouter.helpers.AskStreamErrorMapper;
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
import cat.complai.auth.ApiKeyAuthFilter;
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
import io.micronaut.http.sse.Event;
import jakarta.inject.Inject;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Primary HTTP controller for the ComplAI conversational API.
 *
 * <p>Exposes two endpoints under {@code /complai}:
 * <ul>
 *   <li>{@code POST /complai/ask} — answers a citizen's question about El Prat de Llobregat
 *       using Server-Sent Events (SSE) streaming. Falls back to plain JSON when the client
 *       does not accept {@code text/event-stream}.</li>
 *   <li>{@code POST /complai/redact} — drafts a formal complaint letter. When the caller
 *       supplies a complete complainant identity and requests PDF format the controller
 *       enqueues a {@link RedactSqsMessage} and returns {@code 202 Accepted} immediately;
 *       otherwise a synchronous JSON letter is returned.</li>
 * </ul>
 *
 * <p>All POST requests require a valid {@code X-Api-Key} header, enforced upstream by
 * {@link ApiKeyAuthFilter}. The validated city identifier is read from the request attribute
 * {@link ApiKeyAuthFilter#CITY_ATTRIBUTE} and passed into every service call.
 *
 * <p>OIDC identity verification (via the optional {@link OidcIdentityTokenValidator}) is
 * applied to the redact flow when enabled for the caller's city. When enabled the
 * {@code X-Identity-Token} header is mandatory and overrides any self-reported body fields.
 *
 * <p>Every request is audit-logged via {@link AuditLogger} with only metadata
 * (endpoint, request hash, error code, latency) — no user text or AI response is ever logged.
 */
@Controller("/complai")
public class OpenRouterController {

    private final IOpenRouterService service;
    private final SqsComplaintPublisher sqsPublisher;
    private final S3PdfUploader s3PdfUploader;
    // Null when jwt.secret is not configured (worker Lambda). When non-null,
    // individual
    // cities may still have verification disabled — check isEnabledForCity() before
    // use.
    private final OidcIdentityTokenValidator identityTokenValidator;
    private final Logger logger = Logger.getLogger(OpenRouterController.class.getName());

    @Inject
    public OpenRouterController(IOpenRouterService service,
            SqsComplaintPublisher sqsPublisher,
            S3PdfUploader s3PdfUploader,
            @Nullable OidcIdentityTokenValidator identityTokenValidator) {
        this.service = service;
        this.sqsPublisher = sqsPublisher;
        this.s3PdfUploader = s3PdfUploader;
        this.identityTokenValidator = identityTokenValidator;
    }

    /**
     * Answers a citizen's question about El Prat de Llobregat using SSE streaming.
     *
     * <p>The response is a {@code text/event-stream} of JSON-encoded SSE events:
     * <ol>
     *   <li>{@code sources} — procedure/document references retrieved from the RAG index</li>
     *   <li>{@code response} — zero or more streamed text chunks for real-time display</li>
     *   <li>{@code complete} — final event with success/error status and full assembled text</li>
     * </ol>
     *
     * <p>On a pre-stream validation or service error, a plain JSON error response is returned
     * instead of starting the SSE stream.
     *
     * @param request     the ask request body containing the citizen's question and optional
     *                    conversation ID
     * @param httpRequest the HTTP request, used to extract the city attribute set by
     *                    {@link ApiKeyAuthFilter}
     * @return {@code 200 OK} with a streaming SSE body on success, or an appropriate
     *         error response ({@code 400}, {@code 422}, {@code 502}, {@code 504}) on failure
     */
    @Post("/ask")
        @Produces({ MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON })
        public HttpResponse<?> ask(@Body AskRequest request, HttpRequest<?> httpRequest) {
        String cityId = httpRequest.getAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, String.class)
                .orElseThrow(() -> new IllegalStateException(
                        "city attribute missing from request — API key filter should have set it"));
        String conversationId = request != null ? request.getConversationId() : null;
        int inputLength = request != null && request.getText() != null ? request.getText().length() : 0;
        logger.info(() -> "POST /complai/ask (stream) received — conversationId=" + conversationId
                + " inputLength=" + inputLength + " city=" + cityId);
        long start = System.currentTimeMillis();

        String questionText = request != null ? request.getText() : null;

        AskStreamResult streamResult = service.streamAsk(questionText, conversationId, cityId);
        if (streamResult instanceof AskStreamResult.Error error) {
            OpenRouterResponseDto dto = error.errorResponse();
            long latency = System.currentTimeMillis() - start;
            OpenRouterErrorCode errorCode = dto != null ? dto.getErrorCode() : OpenRouterErrorCode.INTERNAL;
            AuditLogger.log("/complai/ask",
                    AuditLogger.hashText(questionText),
                    errorCode.getCode(), latency, null, null);
            if (errorCode == OpenRouterErrorCode.UPSTREAM || errorCode == OpenRouterErrorCode.TIMEOUT) {
                logger.warning(() -> "POST /complai/ask rejected before stream start — conversationId="
                        + conversationId + " errorCode=" + errorCode + " latencyMs=" + latency
                        + " upstreamStatus=" + (dto != null ? dto.getStatusCode() : null));
            } else {
                logger.log(Level.SEVERE, "POST /complai/ask failed before stream start — conversationId="
                        + conversationId + " latencyMs=" + latency);
            }
            return errorToHttpResponse(dto, "ask");
        }

        Publisher<String> eventStream = ((AskStreamResult.Success) streamResult).stream();

        Publisher<Event<String>> responseBody = Flux.from(eventStream)
                .map(Event::of)  // Event stream emits JSON strings; wrap directly without escaping
                .doOnComplete(() -> {
                    long latency = System.currentTimeMillis() - start;
                    AuditLogger.log("/complai/ask",
                            AuditLogger.hashText(questionText),
                            OpenRouterErrorCode.NONE.getCode(), latency, null, null);
                    logger.info(() -> "POST /complai/ask (stream) completed — conversationId="
                            + conversationId + " latencyMs=" + latency);
                })
                .doOnError(e -> {
                    long latency = System.currentTimeMillis() - start;
                    OpenRouterErrorCode errorCode = AskStreamErrorMapper.resolveErrorCode(e);
                    AuditLogger.log("/complai/ask",
                            AuditLogger.hashText(questionText),
                            errorCode.getCode(), latency, null, null);
                    Level logLevel = errorCode == OpenRouterErrorCode.INTERNAL ? Level.SEVERE : Level.WARNING;
                    logger.log(logLevel, "POST /complai/ask (stream) error — conversationId="
                            + conversationId + " latencyMs=" + latency + " errorCode=" + errorCode, e);
                });
        return HttpResponse.ok(responseBody).contentType(MediaType.TEXT_EVENT_STREAM_TYPE);
    }

    /**
     * Drafts a formal complaint letter addressed to the Ajuntament.
     *
     * <p>Two processing paths exist:
     * <ul>
     *   <li><b>Async PDF path</b>: when the identity is complete (name + surname + ID) and the
     *       requested format is {@code pdf}, the controller validates the input, generates a
     *       pre-signed S3 URL, publishes a {@link RedactSqsMessage} to SQS, and returns
     *       {@code 202 Accepted} with the URL. The worker Lambda produces the PDF asynchronously.</li>
     *   <li><b>Synchronous path</b>: when the identity is incomplete or format is {@code json},
     *       the letter (or an AI question requesting missing identity fields) is returned
     *       synchronously as {@code 200 OK} JSON.</li>
     * </ul>
     *
     * <p>When OIDC is enabled for the caller's city, the {@code X-Identity-Token} header is
     * required and its validated claims override any self-reported body fields.
     *
     * @param request     the redact request body
     * @param httpRequest the HTTP request, used to extract the city attribute and OIDC token header
     * @return {@code 202 Accepted} for async PDF, {@code 200 OK} for synchronous letter, or an
     *         appropriate error response
     */
    @Post("/redact")
    @Produces({ MediaType.APPLICATION_JSON })
    public HttpResponse<?> redact(@Body RedactRequest request, HttpRequest<?> httpRequest) {
        String cityId = httpRequest.getAttribute(ApiKeyAuthFilter.CITY_ATTRIBUTE, String.class)
                .orElseThrow(() -> new IllegalStateException(
                        "city attribute missing from request — API key filter should have set it"));
        String conversationId = request != null ? request.getConversationId() : null;
        int inputLength = request != null && request.getText() != null ? request.getText().length() : 0;
        OutputFormat requestedFormat = request != null ? request.getFormat() : null;
        logger.info(() -> "POST /complai/redact received — conversationId=" + conversationId
                + " inputLength=" + inputLength + " format=" + requestedFormat + " city=" + cityId);
        long start = System.currentTimeMillis();
        try {
            String text = request != null ? request.getText() : null;
            OutputFormat format = request == null ? OutputFormat.PDF : request.getFormat();
            ComplainantIdentity identity = request != null ? request.getComplainantIdentity() : null;

            if (!OutputFormat.isSupportedClientFormat(format)) {
                long latency = System.currentTimeMillis() - start;
                AuditLogger.log("/complai/redact", AuditLogger.hashText(text),
                        OpenRouterErrorCode.VALIDATION.getCode(), latency, format != null ? format.name() : null, null);
                logger.info(() -> "POST /complai/redact rejected — httpStatus=400 reason=unsupportedFormat"
                        + " format=" + format + " latencyMs=" + latency + " conversationId=" + conversationId);
                OpenRouterPublicDto err = new OpenRouterPublicDto(false, null,
                        "Unsupported format. Only 'pdf' is accepted.",
                        OpenRouterErrorCode.VALIDATION.getCode(), List.of());
                return HttpResponse.badRequest(err).contentType(MediaType.APPLICATION_JSON);
            }

            // When OIDC is enabled for this city (via oidc-mapping.json), the
            // X-Identity-Token header is mandatory. The verified IdP identity overrides
            // any self-reported body fields, ensuring the PDF carries a cryptographically
            // verified NIF/NIE rather than user-supplied data.
            //
            // If the header is absent or blank, we return 401 Unauthorized.
            // If the header is present but invalid, we also return 401.
            if (identityTokenValidator != null && identityTokenValidator.isEnabledForCity(cityId)) {
                String rawIdentityToken = httpRequest.getHeaders().get("X-Identity-Token");
                if (rawIdentityToken == null || rawIdentityToken.isBlank()) {
                    long latency = System.currentTimeMillis() - start;
                    AuditLogger.log("/complai/redact", AuditLogger.hashText(text),
                            OpenRouterErrorCode.UNAUTHORIZED.getCode(), latency,
                            format != null ? format.name() : null, null);
                    logger.warning(() -> "POST /complai/redact rejected — httpStatus=401"
                            + " reason=missingIdentityToken"
                            + " conversationId=" + conversationId);
                    OpenRouterPublicDto err = new OpenRouterPublicDto(false, null,
                            "Identity verification is required. Please authenticate via your city's identity provider.",
                            OpenRouterErrorCode.UNAUTHORIZED.getCode(), List.of());
                    return HttpResponse.unauthorized().body(err)
                            .contentType(MediaType.APPLICATION_JSON);
                }
                try {
                    VerifiedCitizenIdentity verified = identityTokenValidator.validate(rawIdentityToken, cityId);
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
                            OpenRouterErrorCode.UNAUTHORIZED.getCode(), List.of());
                    return HttpResponse.unauthorized().body(err)
                            .contentType(MediaType.APPLICATION_JSON);
                }
            }

            boolean identityComplete = identity != null && identity.isComplete();

            if (identityComplete) {
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
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null,
                    "An internal error occurred. Please try again later.",
                    OpenRouterErrorCode.INTERNAL.getCode(), List.of());
            return HttpResponse.serverError(err).contentType(MediaType.APPLICATION_JSON);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Validates, enqueues, and returns a {@code 202 Accepted} response containing
     * the
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
                    "Failed to prepare complaint storage URL.", OpenRouterErrorCode.INTERNAL.getCode(), List.of());
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
                    OpenRouterErrorCode.INTERNAL.getCode(), List.of());
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
            case "CA" ->
                "La vostra carta de reclamació s'està generant. Estarà disponible d'aquí a pocs minuts a l'adreça de sota.";
            case "ES" -> "Su carta de queja se está generando. Estará disponible en breve en la dirección siguiente.";
            default -> "Your complaint letter is being created. It will be available shortly at the URL below.";
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
     * <p>
     * The folder prefix is the conversationId when provided (groups multi-turn
     * complaints)
     * or a fresh UUID otherwise. The timestamp prefix inside the folder enables
     * chronological
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
     * Maps a service response to the appropriate HTTP status. The errorCode on the
     * DTO is the
     * authoritative signal; the error message is only consulted as a fallback for
     * legacy responses
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
                logger.fine(() -> operation + ": httpStatus=400 errorCode=VALIDATION — "
                        + (dto != null ? dto.getError() : "validation error"));
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
                logger.warning(() -> operation + ": httpStatus=502 errorCode=UPSTREAM — "
                        + (dto != null ? dto.getError() : "upstream error"));
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
