package assistant.reader;

import assistant.common.Problem;
import assistant.common.Store;
import assistant.knowledge.Citations;
import assistant.knowledge.Documents;
import assistant.knowledge.Passages;
import assistant.knowledge.SourceFiles;
import org.springframework.stereotype.Service;

@Service
public class OpenCitation {
    private final Citations citations;
    private final Documents documents;
    private final Store db;

    public OpenCitation(Citations citations, Documents documents, Store db) {
        this.citations = citations;
        this.documents = documents;
        this.db = db;
    }

    /** Resolves an immutable citation against current original content without changing bookmarks. docs/api/knowledge.md#k03 */
    public View open(String id) {
        var reference = citations.get(id);
        var doc = documents.find(reference.documentId());
        if (doc.status().equals("DELETED")) {
            return view(doc, reference, null, true, "资料已删除");
        }
        if (doc.version() == null) {
            return view(doc, reference, null, true, "原文暂时无法读取");
        }
        boolean changed = !reference.version().equals(doc.version());
        try {
            if (!changed) {
                documents.content(doc, doc.version());
                return view(doc, reference, db.read(reference.anchor(), SourceFiles.Anchor.class), false, null);
            }
            var match = Passages.locate(documents.sections(doc, doc.version()), reference.excerpt(), reference.context());
            return view(doc, reference, match.anchor(), true, match.error());
        } catch (Problem e) {
            return view(doc, reference, null, changed || e.status() == 409, e.getMessage());
        }
    }

    private View view(Documents.Document doc, Citations.Record reference, SourceFiles.Anchor anchor,
                      boolean changed, String reason) {
        return new View(doc.view(), anchor, reference.excerpt(), changed, reason);
    }

    public record View(Documents.View document, SourceFiles.Anchor anchor, String excerpt, boolean changed, String reason) {}
}
