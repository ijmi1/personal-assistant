package assistant.http;

import assistant.task.OpenTask;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TaskRoutes {
    private final OpenTask tasks;

    TaskRoutes(OpenTask tasks) { this.tasks = tasks; }

    @GetMapping({"/api/tasks", "/api/v1/tasks"})
    Object tasks() { return tasks.query(null); }

    @GetMapping({"/api/tasks/{id}", "/api/v1/tasks/{id}"})
    Object task(@PathVariable String id) { return tasks.query(id).getFirst(); }
}
