package assistant.mail;

import assistant.common.Problem;
import assistant.common.Store;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

@Service
public class Threads {
    private final Store db;

    public Threads(Store db) { this.db = db; }

    public List<Summary> list() {
        return db.list(Summary.class, """
                select t.id,t.subject,t.updated_at,count(m.id) as message_count,
                (select id from mail_messages where thread_id=t.id and direction='IN' order by seq desc limit 1) latest_message_id
                from threads t join mail_messages m on t.id=m.thread_id
                group by t.id order by t.updated_at desc
                """);
    }

    /** Returns the thread up to the selected incoming message, ordered by stored arrival sequence. docs/api/mail.md#history */
    public History history(String threadId, String through) {
        var thread = db.one(Title.class, "select id,subject from threads where id=?", threadId)
                .orElseThrow(() -> Problem.missing("往来不存在"));
        long end = through == null ? Long.MAX_VALUE : db.one(Long.class, """
                select seq from mail_messages where id=? and thread_id=?
                """, through, threadId).orElseThrow(() -> Problem.missing("该来信不在当前往来中"));
        var messages = db.list(MessageRow.class, """
                select id,header_id,sender,reply_to,subject,body,sent_at,direction
                from mail_messages where thread_id=? and seq<=? order by seq
                """, threadId, end);
        return new History(thread.id(), thread.subject(), messages.stream().map(this::view).toList());
    }

    public boolean hasNewIncoming(String thread, String selected) {
        return db.one(Boolean.class, """
                select exists(select 1 from mail_messages m where m.thread_id=? and direction='IN'
                and m.seq>(select seq from mail_messages where id=?))
                """, thread, selected).orElse(false);
    }

    public String header(String messageId) {
        return db.one(String.class, "select header_id from mail_messages where id=? and header_id is not null", messageId)
                .orElse(null);
    }

    /** Stores acknowledged outbound correspondence in the existing thread. Repeated Message-IDs are reused. */
    public void recordOutgoing(String thread, String messageId, String from, String subject, String body) {
        db.lock("outgoing:" + messageId);
        if (db.one(String.class, "select id from mail_messages where header_id=? and direction='OUT' limit 1", messageId).isPresent()) {
            return;
        }
        db.update("""
                insert into mail_messages (id,thread_id,header_id,fingerprint,sender,reply_to,subject,body,sent_at,direction)
                values (?,?,?,?,?,'[]',?,?,now(),'OUT')
                """, Store.id(), thread, messageId, messageId, from, subject, body);
        db.update("update threads set updated_at=now() where id=?", thread);
    }

    private Message view(MessageRow row) {
        return new Message(row.id(), row.headerId(), row.sender(), Arrays.asList(db.read(row.replyTo(), String[].class)),
                row.subject(), row.body(), row.sentAt(), row.direction());
    }

    public record Summary(String id, String subject, OffsetDateTime updatedAt, long messageCount, String latestMessageId) {}
    public record Title(String id, String subject) {}
    public record History(String id, String subject, List<Message> messages) {}
    public record Message(String id, String headerId, String sender, List<String> replyTo, String subject,
                          String body, OffsetDateTime sentAt, String direction) {}
    public record MessageRow(String id, String headerId, String sender, String replyTo, String subject,
                             String body, OffsetDateTime sentAt, String direction) {}
}
