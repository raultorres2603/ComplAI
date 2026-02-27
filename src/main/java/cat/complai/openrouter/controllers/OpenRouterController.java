package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.controllers.dto.AskRequest;
import cat.complai.openrouter.controllers.dto.RedactRequest;
import cat.complai.openrouter.dto.OutputFormat;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import jakarta.inject.Inject;

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
        try {
            OpenRouterResponseDto dto = service.ask(request.getText());
            return errorToHttpResponse(dto, "ask");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "ask: unexpected exception", e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null, e.getMessage(), OpenRouterErrorCode.INTERNAL.getCode());
            return HttpResponse.serverError(err);
        }
    }

    @Post("/redact")
    @Produces({MediaType.APPLICATION_JSON, MediaType.APPLICATION_PDF})
    public HttpResponse<?> redact(@Body RedactRequest request) {
        logger.info("POST /openrouter/redact called");
        try {
            String text = request != null ? request.getText() : null;
            OutputFormat format = request == null ? OutputFormat.AUTO : request.getFormat();

            // Reject unsupported format values at the HTTP boundary before touching the service.
            // Only PDF, JSON, and AUTO are valid. Any other value (e.g. "xml", "docx") means the
            // client sent something we will never support: only PDF is produced as a document.
            if (!OutputFormat.isSupportedClientFormat(format)) {
                OpenRouterPublicDto err = new OpenRouterPublicDto(
                        false, null,
                        "Unsupported format. Only 'pdf', 'json', or 'auto' are accepted. Documents are always produced as PDF.",
                        OpenRouterErrorCode.VALIDATION.getCode()
                );
                return HttpResponse.badRequest(err);
            }

            OpenRouterResponseDto dto = service.redactComplaint(text, format);

            // If PDF data present and success, return it directly as application/pdf.
            // Content-Length must be set explicitly: without it Netty cannot determine the body
            // boundary, falls back to Connection: close, and the client fires channelInactive
            // before reading the body — causing ResponseClosedException in tests and in production.
            if (dto != null && dto.isSuccess() && dto.getPdfData() != null) {
                byte[] pdf = dto.getPdfData();
                return HttpResponse.ok(pdf)
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(io.micronaut.http.HttpHeaders.CONTENT_LENGTH, String.valueOf(pdf.length));
            }

            return errorToHttpResponse(dto, "redact");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "redact: unexpected exception", e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null, e.getMessage(), OpenRouterErrorCode.INTERNAL.getCode());
            return HttpResponse.serverError(err);
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
            return HttpResponse.ok(publicDto);
        }

        OpenRouterErrorCode errorCode = dto != null ? dto.getErrorCode() : OpenRouterErrorCode.INTERNAL;

        return switch (errorCode) {
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
    }
}
