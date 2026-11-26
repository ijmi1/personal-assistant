package assistant.task;

import assistant.common.Problem;
import assistant.common.Store;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;

@Service
public class Jobs {
    static final TaskDescriptor<TaskTracing.Link> TASK = TaskDescriptor.of("assistant-job", TaskTracing.Link.class);
    private final Store db;
    private final TaskMapper tasks;
    private final SchedulerClient scheduler;
    private final TaskTracing tracing;
    private final String owner;
    private final int aiJobsPerMinute;

    public Jobs(Store db, TaskMapper tasks, SchedulerClient scheduler, TaskTracing tracing, @Value("${assistant.owner}") String owner,
                @Value("${assistant.jobs.ai-per-minute}") int aiJobsPerMinute) {
        if (aiJobsPerMinute < 1) {
            throw new IllegalArgumentException("ASSISTANT_AI_JOBS_PER_MINUTE 必须大于零");
        }
        this.db = db;
        this.tasks = tasks;
        this.scheduler = scheduler;
        this.tracing = tracing;
        this.owner = owner;
        this.aiJobsPerMinute = aiJobsPerMinute;
    }

    /** Persists the job and its schedule together. A non-null scope merges active work. docs/api/tasks.md#jobs */
    public Accepted submit(JobSpec job, String key) {
        return db.keyed(owner, job.type(), key, job, Accepted.class, () -> enqueue(job));
    }

    private Accepted enqueue(JobSpec job) {
        if (job.scope() != null) {
            db.lock(db.write(List.of("active-task", job.type(), job.scope())));
            var active = tasks.selectList(new QueryWrapper<TaskRow>()
                    .eq("type", job.type()).eq("scope", job.scope()).in("status", "QUEUED", "RUNNING"));
            if (!active.isEmpty()) {
                return new Accepted(active.getFirst().id);
            }
        }
        if (job.type().equals("QUESTION") || job.type().equals("DRAFT")) {
            checkAiQuota();
        }
        TaskRow row = new TaskRow();
        row.id = Store.id();
        row.type = job.type();
        row.input = job.input();
        row.scope = job.scope();
        row.status = "QUEUED";
        row.step = "等待后台处理";
        tasks.insert(row);
        scheduler.scheduleIfNotExists(TASK.instance(row.id).data(tracing.capture()).build(), Instant.now());
        return new Accepted(row.id);
    }

    private void checkAiQuota() {
        db.lock(db.write(List.of("ai-job-quota", owner)));
        long count = db.one(Long.class, """
                select count(*) from tasks where type in ('QUESTION', 'DRAFT')
                and created_at > statement_timestamp() - interval '1 minute'
                """).orElseThrow();
        if (count >= aiJobsPerMinute) {
            throw Problem.rateLimited("提问和拟稿过于频繁，请一分钟后再试");
        }
    }

    public record JobSpec(String type, String input, String scope) {}
    public record Accepted(String taskId) {}
    public record Result(String type, String id) {}
    public record TaskView(String id, String type, String step, String status, String resultType,
                           String resultId, String error, OffsetDateTime updatedAt) {
        static TaskView from(TaskRow row) {
            return new TaskView(row.id, row.type, row.step, row.status, row.resultType, row.resultId,
                    row.error, row.updatedAt);
        }
    }

    public interface Handler {
        String type();
        /** Computes work outside the result transaction and returns the atomic publication step. */
        Supplier<Result> prepare(Progress progress, String input);
    }

    public record Progress(String id, int attempt, Store db) {
        public void step(String message) {
            if (db.update("update tasks set step=?,updated_at=now() where id=? and attempts=? and status='RUNNING'",
                    message, id, attempt) != 1) {
                throw Problem.conflict("任务已由其他执行接手");
            }
        }

        /** Checks the execution while holding the task row lock until commit. */
        public boolean ownsRun() {
            return db.one(Integer.class, "select attempts from tasks where id=? and status='RUNNING' for update", id)
                    .filter(value -> value == attempt).isPresent();
        }
    }
}
