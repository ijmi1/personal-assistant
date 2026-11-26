package assistant.mail;

import assistant.common.Problem;
import assistant.common.Store;
import assistant.task.Jobs;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

@Component
class MailboxJob implements Jobs.Handler {
    private static final String INBOX = "INBOX";
    private final Store db;
    private final MailboxConnection connection;

    MailboxJob(Store db, MailboxConnection connection) {
        this.db = db;
        this.connection = connection;
    }

    @Override public String type() { return "MAIL"; }

    @Override
    public Supplier<Jobs.Result> prepare(Jobs.Progress progress, String account) {
        if (!connection.configured() || !account.equals(connection.account())) {
            throw Problem.unavailable("任务对应的邮箱配置已变更");
        }
        progress.step("连接 IMAP 邮箱");
        try {
            connection.read(INBOX, folder -> {
                receive(progress, account, folder);
                return null;
            });
        } catch (AuthenticationFailedException e) {
            throw Problem.unavailable("邮箱认证失败，请检查 IMAP 配置");
        } catch (MessagingException e) {
            throw Problem.unavailable("邮箱读取中断，将从已保存的游标继续");
        }
        return () -> new Jobs.Result("threads", null);
    }

    private void receive(Jobs.Progress progress, String account, IMAPFolder folder) throws MessagingException {
        long validity = folder.getUIDValidity();
        var cursor = db.one(Cursor.class, "select uid_validity,last_uid from mail_cursor where account=? and folder=?",
                account, INBOX).orElse(new Cursor(validity, 0));
        long after = cursor.uidValidity() == validity ? cursor.lastUid() : 0;
        progress.step("读取新来信");
        List<Remote> remote = new ArrayList<>();
        for (Message message : folder.getMessagesByUID(after + 1, UIDFolder.LASTUID)) {
            long uid = folder.getUID(message);
            if (uid > after) {
                remote.add(new Remote(uid, message));
            }
        }
        remote.sort(Comparator.comparingLong(Remote::uid));
        for (var entry : remote) {
            var mail = parse(entry.message());
            db.atomic(() -> store(progress, account, validity, entry.uid(), mail));
        }
        if (remote.isEmpty()) {
            db.atomic(() -> {
                requireCurrent(progress);
                saveCursor(account, validity, after);
            });
        }
    }

    private Incoming parse(Message message) throws MessagingException {
        try {
            var date = message.getSentDate();
            var timestamp = (date == null ? Instant.EPOCH : date.toInstant()).atOffset(ZoneOffset.UTC);
            String sender = MailText.sender(message);
            String subject = MailText.clean(message.getSubject());
            String body = MailText.body(message);
            String fingerprint = Store.hash(db.write(List.of(sender, subject, body, timestamp)).getBytes(StandardCharsets.UTF_8));
            return new Incoming(MailText.headerId(message), MailText.parents(message), sender, MailText.replyTo(message),
                    subject, body, timestamp, fingerprint);
        } catch (IOException e) {
            throw new MessagingException("来信正文无法读取", e);
        }
    }

    private void store(Jobs.Progress progress, String account, long validity, long uid, Incoming mail) {
        requireCurrent(progress);
        String id = db.one(String.class, """
                select message_id from mail_deliveries where account=? and folder=? and uid_validity=? and uid=?
                """, account, INBOX, validity, uid).orElse(null);
        if (id == null) {
            id = duplicate(account, validity, mail);
        }
        if (id == null) {
            id = insert(mail);
        }
        db.update("insert into mail_deliveries values (?,?,?,?,?) on conflict do nothing", account, INBOX, validity, uid, id);
        saveCursor(account, validity, uid);
    }

    private String duplicate(String account, long validity, Incoming mail) {
        if (mail.header() != null) {
            return db.one(String.class, """
                    select id from mail_messages where header_id=? and fingerprint=? and direction='IN' order by seq limit 1
                    """, mail.header(), mail.fingerprint()).orElse(null);
        }
        return db.one(String.class, """
                select m.id from mail_messages m join mail_deliveries d on d.message_id=m.id
                where m.header_id is null and m.fingerprint=? and d.account=? and d.folder=? and d.uid_validity<>?
                order by m.seq limit 1
                """, mail.fingerprint(), account, INBOX, validity).orElse(null);
    }

    private String insert(Incoming mail) {
        String thread = null;
        for (String parent : mail.parents()) {
            var matches = db.list(String.class, "select distinct thread_id from mail_messages where header_id=?", parent);
            if (matches.size() == 1) {
                thread = matches.getFirst();
                break;
            }
        }
        if (thread == null) {
            thread = Store.id();
            db.update("insert into threads (id,subject) values (?,?)", thread, mail.subject());
        }
        String id = Store.id();
        db.update("""
                insert into mail_messages (id,thread_id,header_id,fingerprint,sender,reply_to,subject,body,sent_at)
                values (?,?,?,?,?,?,?,?,?)
                """, id, thread, mail.header(), mail.fingerprint(), mail.sender(), db.write(mail.replyTo()), mail.subject(),
                mail.body(), mail.sentAt());
        db.update("update threads set updated_at=now() where id=?", thread);
        return id;
    }

    private void saveCursor(String account, long validity, long uid) {
        db.update("""
                insert into mail_cursor values (?,?,?,?)
                on conflict (account,folder) do update set uid_validity=excluded.uid_validity,
                    last_uid=case when mail_cursor.uid_validity=excluded.uid_validity
                        then greatest(mail_cursor.last_uid,excluded.last_uid) else excluded.last_uid end
                """, account, INBOX, validity, uid);
    }

    private static void requireCurrent(Jobs.Progress progress) {
        if (!progress.ownsRun()) {
            throw Problem.conflict("收信任务已由其他执行接手");
        }
    }

    public record Cursor(long uidValidity, long lastUid) {}
    private record Remote(long uid, Message message) {}
    private record Incoming(String header, List<String> parents, String sender, List<String> replyTo,
                            String subject, String body, OffsetDateTime sentAt, String fingerprint) {}
}
