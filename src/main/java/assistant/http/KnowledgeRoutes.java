package assistant.http;

import assistant.knowledge.AnswerQuestion;
import assistant.knowledge.Answers;
import assistant.knowledge.Documents;
import assistant.knowledge.SyncDocuments;
import assistant.reader.OpenCitation;
import assistant.reader.ReadingPosition;
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

@RestController
@RequestMapping("/api")
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
    ResponseEntity<?> sync(@RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(sync.sync(key));
    }

    @GetMapping("/documents")
    Object documents() { return documents.list(); }

    @PostMapping("/questions")
    ResponseEntity<?> ask(@RequestBody Question request, @RequestHeader("Idempotency-Key") String key) {
        return ResponseEntity.accepted().body(questions.answer(request.question(), key));
    }

    @GetMapping("/answers/{id}")
    Object answer(@PathVariable String id) { return answers.get(id); }

    @GetMapping("/citations/{id}")
    Object citation(@PathVariable String id) { return citations.open(id); }

    @GetMapping("/documents/{id}/content")
    ResponseEntity<byte[]> content(@PathVariable String id, @RequestParam String version) {
        var file = documents.content(documents.find(id), version);
        var contentType = "PDF".equals(file.kind()) ? MediaType.APPLICATION_PDF
                : MediaType.parseMediaType("text/plain;charset=UTF-8");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(contentType).body(file.bytes());
    }

    @GetMapping("/documents/{id}/outline")
    Object outline(@PathVariable String id, @RequestParam String version) { return documents.outline(id, version); }

    @GetMapping("/documents/{id}/bookmark")
    Object bookmark(Principal owner, @PathVariable String id) {
        return bookmarks.access(owner.getName(), id, null, null);
    }

    @PutMapping("/documents/{id}/bookmark")
    Object bookmark(Principal owner, @PathVariable String id, @RequestBody ReadingPosition.Command input,
                    @RequestHeader("Idempotency-Key") String key) {
        return bookmarks.access(owner.getName(), id, input, key);
    }

    record Question(String question) {}
}
