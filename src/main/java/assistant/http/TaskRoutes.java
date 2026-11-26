package assistant.http;

import assistant.task.OpenTask;
import assistant.task.Jobs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Tag(name = "后台任务", description = "查看同步、问答和拟稿任务的进度与结果")
class TaskRoutes {
    private final OpenTask tasks;

    TaskRoutes(OpenTask tasks) { this.tasks = tasks; }

    @GetMapping({"/api/tasks", "/api/v1/tasks"})
    @Operation(summary = "列出后台任务", description = "按最近更新时间倒序返回当前用户的任务摘要。")
    List<Jobs.TaskView> tasks() { return tasks.query(null); }

    @GetMapping({"/api/tasks/{id}", "/api/v1/tasks/{id}"})
    @Operation(summary = "查询后台任务进度", description = "返回任务阶段、状态以及结果 ID 或错误原因，可用于轮询。")
    Jobs.TaskView task(@Parameter(description = "提交后台任务时返回的 taskId") @PathVariable String id) {
        return tasks.query(id).getFirst();
    }
}
