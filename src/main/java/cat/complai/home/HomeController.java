package cat.complai.home;

import cat.complai.home.dto.HomeDto;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.logging.Logger;

@Controller("/")
public class HomeController {

    private final Logger logger = Logger.getLogger(HomeController.class.getName());

    @Get("/")
    public HomeDto index() {
        logger.info("Accessed home page");
        return new HomeDto("Welcome to ComplAI!", System.currentTimeMillis());
    }
}
