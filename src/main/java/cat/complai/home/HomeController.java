package cat.complai.home;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.logging.Logger;

@Controller("/")
public class HomeController {

    private final Logger logger = Logger.getLogger(HomeController.class.getName());

    @Get("/")
    public String index() {
        logger.info("Accessed home page");
        return "Hello, welcome to ComplAI! This is the home page.";
    }
}
