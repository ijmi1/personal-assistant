package assistant.delivery;

import assistant.common.Store;
import assistant.draft.Drafts;
import assistant.mail.SentEvidence;
import assistant.mail.Threads;
import assistant.task.TaskTracing;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Component
public class DeliveryWorker {
    public static final TaskDescriptor<TaskTracing.Link> TASK = TaskDescriptor.of("assistant-send", TaskTracing.Link.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("assistant.audit");
    private static final String UNKNOWN = "发送结果待核对，已暂停重发";
    private final Store db;
    private final JavaMailSender smtp;
    private final Drafts drafts;
    private final Threads threads;
    private final SentEvidence evidence;

    public DeliveryWorker(Store db, JavaMailSender smtp, Drafts drafts, Threads threads, SentEvidence evidence) {
        this.db = db;
        this.smtp = smtp;
        this.drafts = drafts;
        this.threads = threads;
        this.evidence = evidence;
    }

    /** Claims a queued delivery before touching SMTP. Interrupted claims only move to reconciliation. */
    public void run(String id) {
        var queued = db.one(Row.class, """
                update sends set status='SENDING',updated_at=now() where id=? and status='QUEUED'
                returning id,draft_id,revision,content,message_id,reply_header
                """, id);
        if (queued.isEmpty()) {
            if (db.update("""
                    update sends set status='VERIFY',error=?,updated_at=now() where id=? and status='SENDING'
                    """, UNKNOWN, id) == 1) {
                audit(id, "VERIFY");
            }
            return;
        }
        Row row = queued.get();
        SendDraft.Envelope envelope;
        MimeMessage message;
        try {
            envelope = db.read(row.content(), SendDraft.Envelope.class);
            message = mime(row, envelope);
        } catch (MessagingException | RuntimeException e) {
            failed(row, "邮件无法构建，请核对地址和附件后保存新修订");
            return;
        }
        try {
            smtp.send(message);
        } catch (MailAuthenticationException e) {
            failed(row, "SMTP 邮箱认证失败");
            return;
        } catch (MailException e) {
            Outcome outcome = outcome(e);
            if (outcome == Outcome.REJECTED) {
                failed(row, "邮件服务器拒绝接收，请检查收件地址和邮箱配置");
            } else {
                if (db.update("update sends set status='VERIFY',error=?,partial=?,updated_at=now() where id=? and status in ('SENDING','VERIFY')",
                        UNKNOWN, outcome == Outcome.PARTIAL, row.id()) == 1) {
                    audit(id, "VERIFY");
                }
            }
            return;
        }
        complete(row, envelope);
    }

    private MimeMessage mime(Row row, SendDraft.Envelope envelope) throws MessagingException {
        MimeMessage message = smtp.createMimeMessage();
        var content = envelope.content();
        int mode = content.attachments().isEmpty() ? MimeMessageHelper.MULTIPART_MODE_NO
                : MimeMessageHelper.MULTIPART_MODE_MIXED;
        var helper = new MimeMessageHelper(message, mode, StandardCharsets.UTF_8.name());
        helper.setFrom(envelope.from());
        helper.setTo(content.to().toArray(String[]::new));
        helper.setCc(content.cc().toArray(String[]::new));
        helper.setBcc(content.bcc().toArray(String[]::new));
        helper.setSubject(content.subject());
        helper.setText(content.body(), false);
        for (var item : content.attachments()) {
            helper.addAttachment(item.name(), new ByteArrayResource(item.bytes()), "application/octet-stream");
        }
        if (row.replyHeader() != null) {
            message.setHeader("In-Reply-To", row.replyHeader());
            message.setHeader("References", row.replyHeader());
        }
        message.setHeader("Message-ID", row.messageId());
        return message;
    }

    @Scheduled(fixedDelayString = "${assistant.schedule.reconcile-ms}", initialDelayString = "${assistant.schedule.reconcile-ms}")
    void reconcile() {
        List<Row> pending = db.list(Row.class, """
                select id,draft_id,revision,content,message_id,reply_header from sends
                where status='VERIFY' and not partial order by updated_at limit 50
                """);
        var found = evidence.acknowledged(pending.stream().map(Row::messageId).toList());
        pending.stream().filter(row -> found.contains(row.messageId()))
                .forEach(row -> complete(row, db.read(row.content(), SendDraft.Envelope.class)));
    }

    private void complete(Row row, SendDraft.Envelope envelope) {
        boolean updated = db.tx(() -> {
            if (db.update("update sends set status='SENT',error=null,updated_at=now() where id=? and status in ('SENDING','VERIFY')",
                    row.id()) == 1) {
                threads.recordOutgoing(envelope.threadId(), row.messageId(), envelope.from(), envelope.content().subject(), envelope.content().body());
                return true;
            }
            return false;
        });
        if (updated) audit(row.id(), "SENT");
    }

    private void failed(Row row, String reason) {
        boolean updated = db.tx(() -> {
            if (db.update("update sends set status='FAILED',error=?,updated_at=now() where id=? and status in ('SENDING','VERIFY')",
                    reason, row.id()) == 1) {
                drafts.allowRevision(row.draftId(), row.revision());
                return true;
            }
            return false;
        });
        if (updated) audit(row.id(), "FAILED");
    }

    private static void audit(String id, String status) {
        AUDIT.atInfo().addKeyValue("send_id", id).addKeyValue("status", status).log("mail.delivery");
    }

    private static Outcome outcome(MailException error) {
        if (!(error instanceof MailSendException send) || send.getMessageExceptions().length == 0) {
            return Outcome.UNKNOWN;
        }
        var failures = Arrays.asList(send.getMessageExceptions());
        if (failures.stream().anyMatch(exception -> exception instanceof SendFailedException rejected
                && rejected.getValidSentAddresses() != null && rejected.getValidSentAddresses().length > 0)) {
            return Outcome.PARTIAL;
        }
        if (failures.stream().allMatch(exception -> exception instanceof SMTPSendFailedException rejected
                && rejected.getReturnCode() >= 400 && rejected.getReturnCode() < 600)) {
            return Outcome.REJECTED;
        }
        return Outcome.UNKNOWN;
    }

    private enum Outcome { UNKNOWN, PARTIAL, REJECTED }
    public record Row(String id, String draftId, int revision, String content, String messageId, String replyHeader) {}
}
