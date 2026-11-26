package assistant.http;

import assistant.delivery.SendDraft;
import assistant.draft.Outbound;
import assistant.draft.ReplyDraft;
import assistant.mail.SyncMailbox;
import assistant.mail.Threads;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping({"/api", "/api/v1"})
class MailRoutes {
    private final SyncMailbox mailbox;
    private final Threads threads;
    private final ReplyDraft drafts;
    private final SendDraft delivery;

    MailRoutes(SyncMailbox mailbox, Threads threads, ReplyDraft drafts, SendDraft delivery) {
        this.mailbox = mailbox;
        this.threads = threads;
        this.drafts = drafts;
        this.delivery = delivery;
    }

    @PostMapping("/mail/sync")
    ResponseEntity<?> sync(@RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(mailbox.sync(key));
    }

    @GetMapping("/threads")
    Object list() { return threads.list(); }

    @GetMapping("/threads/{id}")
    Object history(@PathVariable String id, @RequestParam(required = false) String through) {
        return threads.history(id, through);
    }

    @PostMapping("/threads/{id}/drafts")
    ResponseEntity<?> generate(Principal owner, @PathVariable String id, @RequestBody Generate input,
                               @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(drafts.manage(owner.getName(), new ReplyDraft.Generate(id, input.messageId(), key)));
    }

    @GetMapping("/drafts/{id}")
    Object preview(Principal owner, @PathVariable String id) {
        return drafts.manage(owner.getName(), new ReplyDraft.Preview(id));
    }

    @PatchMapping("/drafts/{id}")
    Object save(Principal owner, @PathVariable String id, @RequestBody Save input, @RequestHeader("Idempotency-Key") String key) {
        return drafts.manage(owner.getName(), new ReplyDraft.Save(id, input.revision(), input.content(), key));
    }

    @DeleteMapping("/drafts/{id}")
    Object discard(Principal owner, @PathVariable String id, @RequestBody Revision input,
                   @RequestHeader("Idempotency-Key") String key) {
        return drafts.manage(owner.getName(), new ReplyDraft.Discard(id, input.revision(), key));
    }

    @PostMapping("/drafts/{id}/send")
    ResponseEntity<?> confirm(@PathVariable String id, @RequestBody Revision input) {
        return ResponseEntity.accepted().body(delivery.confirm(id, input.revision()));
    }

    @GetMapping("/sends/{id}")
    Object send(@PathVariable String id) { return delivery.get(id); }

    @GetMapping("/sends")
    Object sends(@RequestParam(required = false) String draftId) { return delivery.list(draftId); }

    record Generate(String messageId) {}
    record Save(Integer revision, Outbound.Edit content) {}
    record Revision(Integer revision) {}
}
