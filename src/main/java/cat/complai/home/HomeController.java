package cat.complai.home;

import cat.complai.home.dto.HomeDto;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.logging.Logger;

@Controller()
public class HomeController {

    private final Logger logger = Logger.getLogger(HomeController.class.getName());

    @Get()
    public HttpResponse<HomeDto> index() {
        logger.info("Accessed home page");
        HomeDto homeDto = new HomeDto("Welcome to the Complai Home Page!");
        return HttpResponse.ok(homeDto);
    }
}
