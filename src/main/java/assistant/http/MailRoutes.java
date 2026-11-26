package assistant.http;

import assistant.delivery.SendDraft;
import assistant.draft.Drafts;
import assistant.draft.Outbound;
import assistant.draft.ReplyDraft;
import assistant.mail.SyncMailbox;
import assistant.mail.Threads;
import assistant.task.Jobs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.List;

import static assistant.http.ApiDocumentation.IDEMPOTENCY_KEY;

@RestController
@RequestMapping({"/api", "/api/v1"})
@Tag(name = "邮件与草稿", description = "同步邮件、生成与编辑草稿，以及确认和查询发送结果")
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
    @Operation(summary = "同步邮箱", description = "为已配置的邮箱启动收信任务，并按回复关系归入往来。")
    @ApiResponse(responseCode = "202", description = "已受理。使用 taskId 查询收信进度。",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Jobs.Accepted.class)))
    ResponseEntity<Jobs.Accepted> sync(@Parameter(description = IDEMPOTENCY_KEY, example = "mail-sync-01")
                                      @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(mailbox.sync(key));
    }

    @GetMapping("/threads")
    @Operation(summary = "列出邮件往来", description = "返回每组往来的主题、邮件总数及最新来信 ID。")
    List<Threads.Summary> list() { return threads.list(); }

    @GetMapping("/threads/{id}")
    @Operation(summary = "读取邮件往来", description = "按顺序读取往来中的邮件正文。可指定结束来信，查看当时的往来。")
    Threads.History history(@Parameter(description = "邮件往来列表返回的往来 ID") @PathVariable String id,
                            @Parameter(description = "作为查询终点的来信 ID，包含该封来信。省略时读取完整往来。")
                            @RequestParam(required = false) String through) {
        return threads.history(id, through);
    }

    @PostMapping("/threads/{id}/drafts")
    @Operation(summary = "生成回复草稿", description = "根据指定来信、此前往来和相关资料生成草稿。同一来信拟稿中时复用现有任务。")
    @ApiResponse(responseCode = "202", description = "已受理。使用 taskId 查询拟稿进度。",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Jobs.Accepted.class)))
    ResponseEntity<?> generate(@Parameter(hidden = true) Principal owner,
                               @Parameter(description = "来信所属的邮件往来 ID") @PathVariable String id,
                               @RequestBody Generate input,
                               @Parameter(description = IDEMPOTENCY_KEY, example = "draft-generate-01")
                               @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(drafts.manage(owner.getName(), new ReplyDraft.Generate(id, input.messageId(), key)));
    }

    @GetMapping("/drafts/{id}")
    @Operation(summary = "预览回复草稿", description = "读取当前修订、将要外发的内容、附件元数据和内部核对引文。")
    @ApiResponse(responseCode = "200", description = "当前草稿。",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Drafts.View.class)))
    Object preview(@Parameter(hidden = true) Principal owner,
                   @Parameter(description = "拟稿任务成功完成后的草稿 ID") @PathVariable String id) {
        return drafts.manage(owner.getName(), new ReplyDraft.Preview(id));
    }

    @PatchMapping("/drafts/{id}")
    @Operation(summary = "保存草稿修订", description = "用预期修订号保存编辑后的内容。修订冲突时返回 409 和最新修订。")
    @ApiResponse(responseCode = "200", description = "已保存的新修订。",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Drafts.View.class)))
    Object save(@Parameter(hidden = true) Principal owner,
                @Parameter(description = "要编辑的草稿 ID") @PathVariable String id,
                @RequestBody Save input, @Parameter(description = IDEMPOTENCY_KEY, example = "draft-save-01")
                @RequestHeader("Idempotency-Key") String key) {
        return drafts.manage(owner.getName(), new ReplyDraft.Save(id, input.revision(), input.content(), key));
    }

    @DeleteMapping("/drafts/{id}")
    @Operation(summary = "放弃回复草稿", description = "将指定修订标记为已放弃。已锁定的草稿返回 409。")
    @ApiResponse(responseCode = "200", description = "已放弃的草稿。",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Drafts.View.class)))
    Object discard(@Parameter(hidden = true) Principal owner,
                   @Parameter(description = "要放弃的草稿 ID") @PathVariable String id,
                   @RequestBody Revision input, @Parameter(description = IDEMPOTENCY_KEY, example = "draft-discard-01")
                   @RequestHeader("Idempotency-Key") String key) {
        return drafts.manage(owner.getName(), new ReplyDraft.Discard(id, input.revision(), key));
    }

    @PostMapping("/drafts/{id}/send")
    @Operation(summary = "确认并安排发送",
            description = "核对收件人、主题、正文和附件后，提交已核对的修订号。同一草稿的同一修订重复确认时返回已有记录。")
    @ApiResponse(responseCode = "202", description = "发送已受理。使用返回的 id 查询结果。",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = SendDraft.View.class)))
    ResponseEntity<SendDraft.View> confirm(@Parameter(description = "已核对内容的草稿 ID") @PathVariable String id,
                                           @RequestBody Revision input) {
        return ResponseEntity.accepted().body(delivery.confirm(id, input.revision()));
    }

    @GetMapping("/sends/{id}")
    @Operation(summary = "查询邮件发送结果", description = "返回排队、发送中、已发送、失败或待核对状态，以及可用的错误原因。")
    SendDraft.View send(@Parameter(description = "确认发送时返回的发送记录 ID") @PathVariable String id) {
        return delivery.get(id);
    }

    @GetMapping("/sends")
    @Operation(summary = "列出发送记录", description = "按最近更新时间倒序返回发送记录。")
    List<SendDraft.View> sends(@Parameter(description = "按草稿 ID 筛选。省略时返回全部发送记录。")
                              @RequestParam(required = false) String draftId) {
        return delivery.list(draftId);
    }

    record Generate(@Schema(description = "本往来中要回复的来信 ID") String messageId) {}
    record Save(@Schema(description = "编辑前读取的草稿修订号", example = "2") Integer revision,
                @Schema(description = "编辑后的收件人、主题、正文和附件") Outbound.Edit content) {}
    record Revision(@Schema(description = "要放弃或确认发送的当前草稿修订号", example = "3") Integer revision) {}
}
