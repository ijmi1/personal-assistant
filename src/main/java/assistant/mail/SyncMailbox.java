package assistant.mail;

import assistant.common.Problem;
import assistant.task.Jobs;
import org.springframework.stereotype.Service;

@Service
public class SyncMailbox {
    private final MailboxConnection connection;
    private final Jobs jobs;

    SyncMailbox(MailboxConnection connection, Jobs jobs) {
        this.connection = connection;
        this.jobs = jobs;
    }

    public boolean configured() { return connection.configured(); }

    /** Queues IMAP inbox sync without creating a draft or sending anything. docs/api/mail.md#e01 */
    public Jobs.Accepted sync(String key) {
        if (!configured()) {
            throw Problem.invalid("请先配置 IMAP 邮箱");
        }
        return jobs.submit(new Jobs.JobSpec("MAIL", connection.account(), connection.account()), key);
    }
}
