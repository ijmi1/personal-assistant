package assistant.task;

import assistant.common.Problem;
import assistant.common.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
class JobRunner {
    private static final Logger LOG = LoggerFactory.getLogger(JobRunner.class);
    private final Store db;
    private final Map<String, Jobs.Handler> handlers;

    JobRunner(Store db, List<Jobs.Handler> handlers) {
        this.db = db;
        this.handlers = handlers.stream().collect(Collectors.toMap(Jobs.Handler::type, Function.identity()));
    }

    void run(String id) {
        var run = db.one(Run.class, """
                update tasks set status='RUNNING',step='开始处理',attempts=attempts+1,updated_at=now()
                where id=? and status in ('QUEUED','RUNNING')
                returning type,input,attempts,cast(extract(epoch from now()-created_at)*1000 as bigint) as waiting_ms
                """, id);
        if (run.isEmpty()) {
            return;
        }
        var job = run.get();
        LOG.info("开始后台任务 {}，类型 {}，等待 {} ms，执行次数 {}", id, job.type(), job.waitingMs(), job.attempts());
        var progress = new Jobs.Progress(id, job.attempts(), db);
        try {
            if (job.attempts() > 3) {
                throw Problem.unavailable("后台多次中断，请重新提交任务");
            }
            var publish = handlers.get(job.type()).prepare(progress, job.input());
            db.atomic(() -> {
                if (progress.ownsRun()) {
                    Jobs.Result result = publish.get();
                    db.update("""
                            update tasks set status='DONE',step='已完成',result_type=?,result_id=?,error=null,updated_at=now()
                            where id=?
                            """, result.type(), result.id(), id);
                }
            });
        } catch (RuntimeException error) {
            String reason = error instanceof Problem ? error.getMessage() : "后台步骤执行失败，请重试";
            if (!(error instanceof Problem)) {
                LOG.warn("任务 {} 的 {} 步骤失败 ({})", id, job.type(), error.getClass().getSimpleName());
            }
            db.update("update tasks set status='FAILED',error=?,updated_at=now() where id=? and attempts=?",
                    reason, id, job.attempts());
        }
    }

    public record Run(String type, String input, int attempts, long waitingMs) {}
}
