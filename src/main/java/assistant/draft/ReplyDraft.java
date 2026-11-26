package assistant.draft;

import assistant.common.Problem;
import assistant.common.Store;
import assistant.mail.Threads;
import assistant.task.Jobs;
import org.springframework.stereotype.Service;

@Service
public class ReplyDraft {
    private final Store db;
    private final Jobs jobs;
    private final Threads threads;
    private final Drafts drafts;

    public ReplyDraft(Store db, Jobs jobs, Threads threads, Drafts drafts) {
        this.db = db;
        this.jobs = jobs;
        this.threads = threads;
        this.drafts = drafts;
    }

    /** Dispatches explicit generation, preview, save, and discard. No operation sends mail. docs/api/mail.md#e02 */
    public Object manage(String owner, Command command) {
        return switch (command) {
            case Generate generate -> generate(generate);
            case Preview preview -> drafts.get(preview.id());
            case Save save -> db.keyed(owner, "DRAFT_SAVE", save.key(), save, Drafts.View.class,
                    () -> drafts.edit(save.id(), revision(save.revision()), save.content()));
            case Discard discard -> db.keyed(owner, "DRAFT_DISCARD", discard.key(), discard, Drafts.View.class,
                    () -> drafts.discard(discard.id(), revision(discard.revision())));
        };
    }

    private Jobs.Accepted generate(Generate command) {
        if (command.messageId() == null || command.messageId().isBlank()) {
            throw Problem.invalid("请选择一封来信");
        }
        var history = threads.history(command.threadId(), command.messageId());
        if (!history.messages().getLast().direction().equals("IN")) {
            throw Problem.invalid("请选择收到的邮件");
        }
        String input = db.write(new GenerateInput(command.threadId(), command.messageId()));
        return jobs.submit(new Jobs.JobSpec("DRAFT", input, command.messageId()), command.key());
    }

    private static int revision(Integer revision) {
        if (revision == null || revision < 1) {
            throw Problem.invalid("需要有效的草稿修订号");
        }
        return revision;
    }

    public sealed interface Command permits Generate, Preview, Save, Discard {}
    public record Generate(String threadId, String messageId, String key) implements Command {}
    public record Preview(String id) implements Command {}
    public record Save(String id, Integer revision, Outbound.Edit content, String key) implements Command {}
    public record Discard(String id, Integer revision, String key) implements Command {}
    public record GenerateInput(String threadId, String messageId) {}
}
