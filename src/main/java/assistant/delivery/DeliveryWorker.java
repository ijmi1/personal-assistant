package assistant.delivery;

import assistant.common.Store;
import assistant.draft.Drafts;
import assistant.mail.SentEvidence;
import assistant.mail.Threads;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
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
    public static final TaskDescriptor<Void> TASK = TaskDescriptor.of("assistant-send", Void.class);
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
                update sends set status='SENDING',updated_at=now() where id=? and status='QUEUED' returning *
                """, id);
        if (queued.isEmpty()) {
            db.update("""
                    update sends set status='VERIFY',error=?,updated_at=now() where id=? and status='SENDING'
                    """, UNKNOWN, id);
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
                db.update("update sends set status='VERIFY',error=?,partial=?,updated_at=now() where id=? and status in ('SENDING','VERIFY')",
                        UNKNOWN, outcome == Outcome.PARTIAL, row.id());
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
        List<Row> pending = db.list(Row.class, "select * from sends where status='VERIFY' and not partial order by updated_at limit 50");
        var found = evidence.acknowledged(pending.stream().map(Row::messageId).toList());
        pending.stream().filter(row -> found.contains(row.messageId()))
                .forEach(row -> complete(row, db.read(row.content(), SendDraft.Envelope.class)));
    }

    private void complete(Row row, SendDraft.Envelope envelope) {
        db.atomic(() -> {
            if (db.update("update sends set status='SENT',error=null,updated_at=now() where id=? and status in ('SENDING','VERIFY')",
                    row.id()) == 1) {
                threads.recordOutgoing(envelope.threadId(), row.messageId(), envelope.from(), envelope.content().subject(), envelope.content().body());
            }
        });
    }

    private void failed(Row row, String reason) {
        db.atomic(() -> {
            if (db.update("update sends set status='FAILED',error=?,updated_at=now() where id=? and status in ('SENDING','VERIFY')",
                    reason, row.id()) == 1) {
                drafts.allowRevision(row.draftId(), row.revision());
            }
        });
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
