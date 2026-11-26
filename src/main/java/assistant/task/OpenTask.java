package assistant.task;

import assistant.common.Problem;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OpenTask {
    private final TaskMapper tasks;

    public OpenTask(TaskMapper tasks) { this.tasks = tasks; }

    /** Returns a single task or a recent-first collection. Unknown IDs return 404. docs/api/tasks.md#m01 */
    public List<Jobs.TaskView> query(String id) {
        if (id == null) {
            return tasks.selectList(new QueryWrapper<TaskRow>().orderByDesc("updated_at"))
                    .stream().map(Jobs.TaskView::from).toList();
        }
        TaskRow row = tasks.selectById(id);
        if (row == null) {
            throw Problem.missing("任务不存在");
        }
        return List.of(Jobs.TaskView.from(row));
    }
}
