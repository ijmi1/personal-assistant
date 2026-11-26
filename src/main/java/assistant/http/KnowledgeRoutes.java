package assistant.http;

import assistant.knowledge.AnswerQuestion;
import assistant.knowledge.Answers;
import assistant.knowledge.Documents;
import assistant.knowledge.SourceFiles;
import assistant.knowledge.SyncDocuments;
import assistant.reader.OpenCitation;
import assistant.reader.ReadingPosition;
import assistant.task.Jobs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
@Tag(name = "资料与阅读", description = "同步学习资料、提问、核对引用和管理阅读书签")
class KnowledgeRoutes {
    private final SyncDocuments sync;
    private final Documents documents;
    private final AnswerQuestion questions;
    private final Answers answers;
    private final OpenCitation citations;
    private final ReadingPosition bookmarks;

    KnowledgeRoutes(SyncDocuments sync, Documents documents, AnswerQuestion questions, Answers answers,
                    OpenCitation citations, ReadingPosition bookmarks) {
        this.sync = sync;
        this.documents = documents;
        this.questions = questions;
        this.answers = answers;
        this.citations = citations;
        this.bookmarks = bookmarks;
    }

    @PostMapping("/index/sync")
    @Operation(summary = "同步资料目录", description = "扫描已配置的资料目录并更新索引。同一目录有扫描任务时复用该任务。")
    @ApiResponse(responseCode = "202", description = "已受理。使用 taskId 查询同步进度。",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Jobs.Accepted.class)))
    ResponseEntity<Jobs.Accepted> sync(@Parameter(description = IDEMPOTENCY_KEY, example = "sync-20261126-01")
                                      @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(sync.sync(key));
    }

    @GetMapping("/documents")
    @Operation(summary = "列出学习资料", description = "返回资料 ID、版本、索引状态和最近更新时间。")
    List<Documents.View> documents() { return documents.list(); }

    @PostMapping("/questions")
    @Operation(summary = "提交资料问题", description = "异步检索资料并生成带引用的回答。完成后从任务结果取得回答 ID。")
    @ApiResponse(responseCode = "202", description = "已受理。使用 taskId 查询问答进度。",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Jobs.Accepted.class)))
    ResponseEntity<Jobs.Accepted> ask(@RequestBody Question request,
                                     @Parameter(description = IDEMPOTENCY_KEY, example = "question-01")
                                     @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(questions.answer(request.question(), key));
    }

    @GetMapping("/answers/{id}")
    @Operation(summary = "读取资料回答", description = "返回回答要点、每个要点的引用 ID，以及资料是否存在证据缺口。")
    Answers.View answer(@Parameter(description = "问答任务成功完成后的结果 ID") @PathVariable String id) {
        return answers.get(id);
    }

    @GetMapping("/citations/{id}")
    @Operation(summary = "定位引用原文", description = "返回原文摘录、资料版本和定位锚点。资料改版后，会返回重新定位的结果或无法定位的原因。")
    OpenCitation.View citation(@Parameter(description = "回答要点或草稿中提供的引用 ID") @PathVariable String id) {
        return citations.open(id);
    }

    @GetMapping("/documents/{id}/content")
    @Operation(summary = "读取资料原文件", description = "按指定版本读取 PDF 或 UTF-8 Markdown。版本过期时重新获取资料版本。")
    @ApiResponse(responseCode = "200", description = "资料原文件。",
            content = {
                    @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
                    @Content(mediaType = "text/plain", schema = @Schema(type = "string"))
            })
    ResponseEntity<byte[]> content(@Parameter(description = "资料列表或引用定位返回的资料 ID") @PathVariable String id,
                                   @Parameter(description = "资料列表或引用定位返回的版本")
                                   @RequestParam String version) {
        var file = documents.content(documents.find(id), version);
        var contentType = "PDF".equals(file.kind()) ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("text/plain;charset=UTF-8");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(contentType).body(file.bytes());
    }

    @GetMapping("/documents/{id}/outline")
    @Operation(summary = "读取 Markdown 区块", description = "返回指定版本的可渲染区块及标题、段落锚点。")
    List<SourceFiles.Unit> outline(@Parameter(description = "Markdown 资料 ID") @PathVariable String id,
                                   @Parameter(description = "资料列表或引用定位返回的版本")
                                   @RequestParam String version) {
        return documents.outline(id, version);
    }

    @GetMapping("/documents/{id}/bookmark")
    @Operation(summary = "读取阅读书签", description = "返回当前用户的阅读位置和书签修订号。首次读取返回起始位置。")
    ReadingPosition.Bookmark bookmark(@Parameter(hidden = true) Principal owner,
                                      @Parameter(description = "要恢复阅读位置的资料 ID") @PathVariable String id) {
        return bookmarks.access(owner.getName(), id, null, null);
    }

    @PutMapping("/documents/{id}/bookmark")
    @Operation(summary = "保存阅读书签",
            description = "提交资料版本、锚点、区块内比例（0 到 1）和预期书签修订号。修订冲突时返回 409 和最新书签。")
    ReadingPosition.Bookmark bookmark(@Parameter(hidden = true) Principal owner,
                                      @Parameter(description = "要保存阅读位置的资料 ID") @PathVariable String id,
                                      @RequestBody ReadingPosition.Command input,
                                      @Parameter(description = IDEMPOTENCY_KEY, example = "bookmark-01")
                                      @RequestHeader("Idempotency-Key") String key) {
        return bookmarks.access(owner.getName(), id, input, key);
    }

    record Question(@Schema(description = "要根据个人资料回答的问题", example = "滑动窗口为什么需要循环收缩？") String question) {}
}
