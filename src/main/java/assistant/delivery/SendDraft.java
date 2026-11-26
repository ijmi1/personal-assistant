package assistant.delivery;

import assistant.common.Problem;
import assistant.common.Store;
import assistant.draft.Drafts;
import assistant.draft.Outbound;
import assistant.mail.Threads;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class SendDraft {
    private final Store db;
    private final Drafts drafts;
    private final Threads threads;
    private final SchedulerClient scheduler;
    private final String from;

    public SendDraft(Store db, Drafts drafts, Threads threads, SchedulerClient scheduler,
                     @Value("${assistant.mail.from}") String from) {
        this.db = db;
        this.drafts = drafts;
        this.threads = threads;
        this.scheduler = scheduler;
        this.from = from;
    }

    /** Freezes the reviewed revision and schedules a single send. Repeats return the original record. docs/api/mail.md#e03 */
    public View confirm(String draftId, Integer reviewed) {
        if (reviewed == null || reviewed < 1) {
            throw Problem.invalid("确认需要已核对的草稿修订");
        }
        return db.tx(() -> {
            db.lock("send:" + draftId);
            var existing = db.one(View.class, """
                    select id,draft_id,revision,status,error,updated_at from sends where draft_id=? and revision=?
                    """, draftId, reviewed);
            return existing.orElseGet(() -> create(draftId, reviewed));
        });
    }

    private View create(String draftId, int revision) {
        var snapshot = drafts.freeze(draftId, revision);
        if (from.isBlank()) {
            throw Problem.unavailable("暂未配置发件邮箱");
        }
        String sender = Outbound.address(from);
        String id = Store.id();
        String messageId = "<" + id + ".assistant@" + sender.substring(sender.lastIndexOf('@') + 1) + ">";
        var envelope = new Envelope(snapshot.threadId(), sender, snapshot.content());
        db.update("""
                insert into sends (id,draft_id,revision,status,content,message_id,reply_header)
                values (?,?,?,'QUEUED',?,?,?)
                """, id, draftId, revision, db.write(envelope), messageId, threads.header(snapshot.messageId()));
        scheduler.scheduleIfNotExists(DeliveryWorker.TASK.instance(id).build(), Instant.now());
        return get(id);
    }

    public View get(String id) {
        return db.one(View.class, "select id,draft_id,revision,status,error,updated_at from sends where id=?", id)
                .orElseThrow(() -> Problem.missing("发送记录不存在"));
    }

    public List<View> list(String draftId) {
        String select = "select id,draft_id,revision,status,error,updated_at from sends";
        return draftId == null ? db.list(View.class, select + " order by updated_at desc")
                : db.list(View.class, select + " where draft_id=? order by updated_at desc", draftId);
    }

    public record View(String id, String draftId, int revision, String status, String error, OffsetDateTime updatedAt) {}
    public record Envelope(String threadId, String from, Outbound content) {}
}
