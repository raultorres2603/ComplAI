package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.controllers.dto.AskRequest;
import cat.complai.openrouter.controllers.dto.RedactRequest;
import cat.complai.openrouter.dto.OutputFormat;
import cat.complai.openrouter.helpers.AuditLogger;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.types.files.StreamedFile;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

@Controller("/complai")
public class OpenRouterController {

    private final IOpenRouterService service;
    private final Logger logger = Logger.getLogger(OpenRouterController.class.getName());

    @Inject
    public OpenRouterController(IOpenRouterService service) {
        this.service = service;
    }

    @Post("/ask")
    public HttpResponse<OpenRouterPublicDto> ask(@Body AskRequest request) {
        logger.info("POST /openrouter/ask called");
        long start = System.currentTimeMillis();
        try {
            OpenRouterResponseDto dto = service.ask(request.getText(), request.getConversationId());
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log(
                    "/complai/ask",
                    AuditLogger.hashText(request.getText()),
                    dto != null ? dto.getErrorCode().getCode() : -1,
                    latency,
                    null, // outputFormat not relevant for ask
                    null  // language (future)
            );
            return errorToHttpResponse(dto, "ask");
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log(
                    "/complai/ask",
                    AuditLogger.hashText(request != null ? request.getText() : null),
                    OpenRouterErrorCode.INTERNAL.getCode(),
                    latency,
                    null,
                    null
            );
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
            String text = request != null ? request.getText() : null;
            OutputFormat format = request == null ? OutputFormat.AUTO : request.getFormat();
            String conversationId = request == null ? null : request.getConversationId();

            if (!OutputFormat.isSupportedClientFormat(format)) {
                long latency = System.currentTimeMillis() - start;
                AuditLogger.log(
                        "/complai/redact",
                        AuditLogger.hashText(text),
                        OpenRouterErrorCode.VALIDATION.getCode(),
                        latency,
                        format != null ? format.name() : null,
                        null
                );
                OpenRouterPublicDto err = new OpenRouterPublicDto(
                        false, null,
                        "Unsupported format. Only 'pdf', 'json', or 'auto' are accepted. Documents are always produced as PDF.",
                        OpenRouterErrorCode.VALIDATION.getCode()
                );
                return HttpResponse.badRequest(err).contentType(MediaType.APPLICATION_JSON);
            }

            OpenRouterResponseDto dto = service.redactComplaint(text, format, conversationId);
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log(
                    "/complai/redact",
                    AuditLogger.hashText(text),
                    dto != null ? dto.getErrorCode().getCode() : -1,
                    latency,
                    format != null ? format.name() : null,
                    null
            );
            if (dto != null && dto.isSuccess() && dto.getPdfData() != null) {
                byte[] pdf = dto.getPdfData();
                // Wrap the byte array in an InputStream and return a StreamedFile
                ByteArrayInputStream inputStream = new ByteArrayInputStream(pdf);
                StreamedFile streamedFile = new StreamedFile(inputStream, MediaType.APPLICATION_PDF_TYPE);

                // 3. Return the StreamedFile.
                // Micronaut will automatically handle the Content-Length header for you!
                return HttpResponse.ok(streamedFile)
                        // Optional: 'inline' tells Chrome/Bruno to display it in the viewer rather than downloading it
                        .header(io.micronaut.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"complaint.pdf\"");
            }
            return errorToHttpResponse(dto, "redact");
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            AuditLogger.log(
                    "/complai/redact",
                    AuditLogger.hashText(request != null ? request.getText() : null),
                    OpenRouterErrorCode.INTERNAL.getCode(),
                    latency,
                    request != null && request.getFormat() != null ? request.getFormat().name() : null,
                    null
            );
            logger.log(Level.SEVERE, "redact: unexpected exception", e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null, e.getMessage(), OpenRouterErrorCode.INTERNAL.getCode());
            return HttpResponse.serverError(err).contentType(MediaType.APPLICATION_JSON);
        }
    }

    /**
     * Maps a service response to the appropriate HTTP status. The errorCode on the DTO is the
     * authoritative signal; the error message is only consulted as a fallback for legacy responses
     * that predate the errorCode field.
     */
    private HttpResponse<OpenRouterPublicDto> errorToHttpResponse(OpenRouterResponseDto dto, String operation) {
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
