package cat.complai.home;

import cat.complai.home.dto.HomeDto;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.logging.Logger;

/**
 * Root HTTP controller for the ComplAI API.
 *
 * <p>Serves a single {@code GET /} welcome response. This endpoint is excluded from
 * API key authentication and is typically used to verify that the Lambda function has
 * started and is reachable. No business logic is executed here.
 */
@Controller()
public class HomeController {

    private final Logger logger = Logger.getLogger(HomeController.class.getName());

    /**
     * Returns a welcome message for the ComplAI home page.
     *
     * @return {@code 200 OK} with a JSON body containing the welcome message
     */
    @Get()
    public HttpResponse<HomeDto> index() {
        logger.fine("GET / — serving home page");
        HomeDto homeDto = new HomeDto("Welcome to the Complai Home Page!");
        return HttpResponse.ok(homeDto);
    }
}
