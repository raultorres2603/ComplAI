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
            OpenRouterPublicDto publicDto = OpenRouterPublicDto.from(dto);
            if (dto.isSuccess()) {
                return HttpResponse.ok(publicDto);
            }
            // Map known validation strings to 400
            if (dto.getErrorCode() == OpenRouterErrorCode.VALIDATION || (dto.getError() != null && (dto.getError().contains("must not be empty") || dto.getError().contains("empty")))) {
                logger.fine("ask: bad request - " + dto.getError());
                return HttpResponse.badRequest(publicDto);
            }
            // Map AI refusal to 422
            if (dto.getErrorCode() == OpenRouterErrorCode.REFUSAL || (dto.getError() != null && dto.getError().equals("Request is not about El Prat de Llobregat."))) {
                logger.info("ask: request out of scope for El Prat");
                return HttpResponse.status(HttpStatus.UNPROCESSABLE_ENTITY).body(publicDto);
            }
            // Map TIMEOUT to 504
            if (dto.getErrorCode() == OpenRouterErrorCode.TIMEOUT) {
                logger.warning("ask: AI service timed out");
                return HttpResponse.status(HttpStatus.GATEWAY_TIMEOUT).body(publicDto);
            }
            // Map upstream AI errors to 502
            if (dto.getErrorCode() == OpenRouterErrorCode.UPSTREAM || (dto.getError() != null && (dto.getError().contains("OPENROUTER") || dto.getError().toLowerCase().contains("openrouter") || dto.getError().toLowerCase().contains("non-2xx")))) {
                logger.log(Level.WARNING, "ask: upstream AI error: {0}", dto.getError());
                return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(publicDto);
            }
            // Generic error -> 500
            logger.log(Level.WARNING, "ask: unexpected error: {0}", dto.getError());
            return HttpResponse.serverError(publicDto);
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
            OutputFormat format = request == null ? OutputFormat.AUTO : request.getFormat();
            OpenRouterResponseDto dto = service.redactComplaint(request.getText(), format);

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

            OpenRouterPublicDto publicDto = OpenRouterPublicDto.from(dto);
            if (dto != null && dto.isSuccess()) {
                return HttpResponse.ok(publicDto);
            }
            // Map known validation strings to 400
            if (dto != null && (dto.getErrorCode() == OpenRouterErrorCode.VALIDATION || (dto.getError() != null && (dto.getError().contains("must not be empty") || dto.getError().contains("empty"))))) {
                logger.fine("redact: bad request - " + dto.getError());
                return HttpResponse.badRequest(publicDto);
            }
            // Map AI refusal to 422
            if (dto != null && (dto.getErrorCode() == OpenRouterErrorCode.REFUSAL || (dto.getError() != null && dto.getError().equals("Request is not about El Prat de Llobregat.")))) {
                logger.info("redact: request out of scope for El Prat");
                return HttpResponse.status(HttpStatus.UNPROCESSABLE_ENTITY).body(publicDto);
            }
            // Map TIMEOUT to 504
            if (dto != null && dto.getErrorCode() == OpenRouterErrorCode.TIMEOUT) {
                logger.warning("redact: AI service timed out");
                return HttpResponse.status(HttpStatus.GATEWAY_TIMEOUT).body(publicDto);
            }
            // Map upstream AI errors to 502
            if (dto != null && (dto.getErrorCode() == OpenRouterErrorCode.UPSTREAM || (dto.getError() != null && (dto.getError().contains("OPENROUTER") || dto.getError().toLowerCase().contains("openrouter") || dto.getError().toLowerCase().contains("non-2xx"))))) {
                logger.log(Level.WARNING, "redact: upstream AI error: {0}", dto.getError());
                return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(publicDto);
            }
            // Generic error -> 500
            logger.log(Level.WARNING, "redact: unexpected error: {0}", dto == null ? "null" : dto.getError());
            return HttpResponse.serverError(publicDto);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "redact: unexpected exception", e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null, e.getMessage(), OpenRouterErrorCode.INTERNAL.getCode());
            return HttpResponse.serverError(err);
        }
    }
}
