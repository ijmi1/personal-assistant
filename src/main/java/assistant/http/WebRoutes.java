package assistant.http;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class WebRoutes {
    @GetMapping({"/documents", "/reader/{id}", "/citation/{id}", "/mail", "/threads/{id}", "/draft/{id}",
            "/send/{id}", "/tasks", "/task/{id}", "/answer/{id}"})
    String app() {
        return "forward:/index.html";
    }
}
