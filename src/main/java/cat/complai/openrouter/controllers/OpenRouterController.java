package cat.complai.openrouter.controllers;

import cat.complai.openrouter.dto.OpenRouterPublicDto;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.interfaces.IOpenRouterService;
import cat.complai.openrouter.controllers.dto.AskRequest;
import cat.complai.openrouter.controllers.dto.RedactRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import jakarta.inject.Inject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.logging.Level;
import java.util.logging.Logger;

@Controller("/openrouter")
public class OpenRouterController {

    private final IOpenRouterService service;
    private final Logger logger = Logger.getLogger(OpenRouterController.class.getName());

    @Inject
    public OpenRouterController(IOpenRouterService service) {
        this.service = service;
    }

    @Operation(summary = "Ask a question related to El Prat de Llobregat")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = OpenRouterPublicDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error", content = @Content)
    @ApiResponse(responseCode = "422", description = "Refusal: not about El Prat", content = @Content)
    @ApiResponse(responseCode = "502", description = "Upstream AI error", content = @Content)
    @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
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
            if (dto.getError() != null && (dto.getError().contains("must not be empty") || dto.getError().contains("empty"))) {
                logger.fine("ask: bad request - " + dto.getError());
                return HttpResponse.badRequest(publicDto);
            }
            // Map AI refusal to 422
            if (dto.getError() != null && dto.getError().equals("Request is not about El Prat de Llobregat.")) {
                logger.info("ask: request out of scope for El Prat");
                return HttpResponse.status(HttpStatus.UNPROCESSABLE_ENTITY).body(publicDto);
            }
            // Map upstream AI errors to 502
            if (dto.getError() != null && (dto.getError().contains("OPENROUTER") || dto.getError().toLowerCase().contains("openrouter") || dto.getError().toLowerCase().contains("non-2xx"))) {
                logger.log(Level.WARNING, "ask: upstream AI error: {0}", dto.getError());
                return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(publicDto);
            }
            // Generic error -> 500
            logger.log(Level.WARNING, "ask: unexpected error: {0}", dto.getError());
            return HttpResponse.serverError(publicDto);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "ask: unexpected exception", e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null, e.getMessage(), 5);
            return HttpResponse.serverError(err);
        }
    }

    @Operation(summary = "Redact a formal letter to the City Hall of El Prat")
    @ApiResponse(responseCode = "200", description = "Success", content = @Content(mediaType = "application/json", schema = @Schema(implementation = OpenRouterPublicDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error", content = @Content)
    @ApiResponse(responseCode = "422", description = "Refusal: not about El Prat", content = @Content)
    @ApiResponse(responseCode = "502", description = "Upstream AI error", content = @Content)
    @ApiResponse(responseCode = "500", description = "Internal server error", content = @Content)
    @Post("/redact")
    public HttpResponse<OpenRouterPublicDto> redact(@Body RedactRequest request) {
        logger.info("POST /openrouter/redact called");
        try {
            OpenRouterResponseDto dto = service.redactComplaint(request.getText());
            OpenRouterPublicDto publicDto = OpenRouterPublicDto.from(dto);
            if (dto.isSuccess()) {
                return HttpResponse.ok(publicDto);
            }
            // Map known validation strings to 400
            if (dto.getError() != null && (dto.getError().contains("must not be empty") || dto.getError().contains("empty"))) {
                logger.fine("redact: bad request - " + dto.getError());
                return HttpResponse.badRequest(publicDto);
            }
            // Map AI refusal to 422
            if (dto.getError() != null && dto.getError().equals("Request is not about El Prat de Llobregat.")) {
                logger.info("redact: request out of scope for El Prat");
                return HttpResponse.status(HttpStatus.UNPROCESSABLE_ENTITY).body(publicDto);
            }
            // Map upstream AI errors to 502
            if (dto.getError() != null && (dto.getError().contains("OPENROUTER") || dto.getError().toLowerCase().contains("openrouter") || dto.getError().toLowerCase().contains("non-2xx"))) {
                logger.log(Level.WARNING, "redact: upstream AI error: {0}", dto.getError());
                return HttpResponse.status(HttpStatus.BAD_GATEWAY).body(publicDto);
            }
            // Generic error -> 500
            logger.log(Level.WARNING, "redact: unexpected error: {0}", dto.getError());
            return HttpResponse.serverError(publicDto);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "redact: unexpected exception", e);
            OpenRouterPublicDto err = new OpenRouterPublicDto(false, null, e.getMessage(), 5);
            return HttpResponse.serverError(err);
        }
    }
}
