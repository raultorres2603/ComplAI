package cat.complai.controllers.home;

import cat.complai.dto.home.HomeDto;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.logging.Logger;

/**
 * Controller for the application root endpoint.
 */
@Controller()
public class HomeController {

    private final Logger logger = Logger.getLogger(HomeController.class.getName());

    /**
     * Returns a welcome message for the root path.
     *
     * @return 200 OK with a {@link HomeDto} welcome message
     */
    @Get()
    public HttpResponse<HomeDto> index() {
        logger.fine("GET / — serving home page");
        HomeDto homeDto = new HomeDto("Welcome to the Complai Home Page!");
        return HttpResponse.ok(homeDto);
    }
}
