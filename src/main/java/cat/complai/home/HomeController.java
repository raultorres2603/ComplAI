package cat.complai.home;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.views.View;
import java.util.Map;

@Controller("/")
public class HomeController {

    @Get("/")
    @View("home.html")
    public Map<String, Object> index() {
        return Map.of("message", "Hello, World!");
    }
}
