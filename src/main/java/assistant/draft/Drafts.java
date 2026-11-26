package assistant.draft;

import assistant.common.Problem;
import assistant.common.Store;
import assistant.mail.Threads;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

@Service
public class Drafts {
    private final Store db;
    private final Threads threads;

    public Drafts(Store db, Threads threads) {
        this.db = db;
        this.threads = threads;
    }

    public View get(String id) { return view(find(id, false)); }

    View edit(String id, int revision, Outbound.Edit content) {
        var row = editable(id, revision);
        Outbound updated = Outbound.edit(content, db.read(row.content(), Outbound.class).attachments());
        db.update("update drafts set content=?,revision=revision+1,status='EDITABLE',updated_at=now() where id=?",
                db.write(updated), id);
        return get(id);
    }

    View discard(String id, int revision) {
        var row = find(id, true);
        if (row.status().equals("DISCARDED") && row.revision() == revision) {
            return view(row);
        }
        requireEditable(row, revision);
        db.update("update drafts set status='DISCARDED',updated_at=now() where id=?", id);
        return get(id);
    }

    private Row editable(String id, int revision) {
        var row = find(id, true);
        requireEditable(row, revision);
        return row;
    }

    private void requireEditable(Row row, int revision) {
        if (row.revision() != revision || !(row.status().equals("EDITABLE") || row.status().equals("REVISABLE"))) {
            throw Problem.conflict("草稿修订已变化或当前不可编辑", view(row));
        }
    }

    /** Locks an editable revision and returns its outbound snapshot inside the confirmation transaction. */
    public Frozen freeze(String id, int reviewed) {
        var row = find(id, true);
        if (row.revision() != reviewed || !row.status().equals("EDITABLE")) {
            throw Problem.conflict("草稿已更新或无法确认，请重新核对", view(row));
        }
        Outbound content = db.read(row.content(), Outbound.class);
        content.requireComplete();
        db.update("update drafts set status='LOCKED',updated_at=now() where id=?", id);
        return new Frozen(row.threadId(), row.messageId(), content);
    }

    /** Makes a definitively rejected delivery editable for a new revision. */
    public void allowRevision(String id, int revision) {
        db.update("update drafts set status='REVISABLE',updated_at=now() where id=? and revision=? and status='LOCKED'",
                id, revision);
    }

    void create(String id, String task, String thread, String message, Outbound content, List<String> citations,
                boolean gap) {
        db.update("""
                insert into drafts (id,task_id,thread_id,message_id,revision,status,content,citation_ids,evidence_gap)
                values (?,?,?,?,1,'EDITABLE',?,?,?)
                """, id, task, thread, message, db.write(content), db.write(citations), gap);
    }

    private Row find(String id, boolean lock) {
        String sql = "select * from drafts where id=?";
        return db.one(Row.class, lock ? sql + " for update" : sql, id)
                .orElseThrow(() -> Problem.missing("草稿不存在"));
    }

    private View view(Row row) {
        return new View(row.id(), row.threadId(), row.messageId(), row.revision(), row.status(),
                db.read(row.content(), Outbound.class).view(), Arrays.asList(db.read(row.citationIds(), String[].class)),
                row.evidenceGap(), threads.hasNewIncoming(row.threadId(), row.messageId()), row.updatedAt());
    }

    public record View(String id, String threadId, String messageId, int revision, String status, Outbound.View content,
                       List<String> citationIds, boolean evidenceGap, boolean newIncoming, OffsetDateTime updatedAt) {}
    public record Row(String id, String taskId, String threadId, String messageId, int revision, String status,
                      String content, String citationIds, boolean evidenceGap, OffsetDateTime updatedAt) {}
    public record Frozen(String threadId, String messageId, Outbound content) {}
}
