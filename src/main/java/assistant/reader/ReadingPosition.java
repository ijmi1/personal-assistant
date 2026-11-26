package assistant.reader;

import assistant.common.Problem;
import assistant.common.Store;
import assistant.knowledge.Documents;
import assistant.knowledge.Passages;
import assistant.knowledge.SourceFiles;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ReadingPosition {
    private final Store db;
    private final Documents documents;

    public ReadingPosition(Store db, Documents documents) {
        this.db = db;
        this.documents = documents;
    }

    /** Reads a bookmark or saves a versioned position. Older writes conflict with the current bookmark. docs/api/knowledge.md#k04 */
    public Bookmark access(String owner, String id, Command command, String key) {
        if (command == null) {
            return restore(documents.find(id), current(owner, id));
        }
        if (command.version() == null || command.anchor() == null || command.revision() == null || command.ratio() == null
                || command.revision() < 0 || !Double.isFinite(command.ratio()) || command.ratio() < 0 || command.ratio() > 1) {
            throw Problem.invalid("书签需要有效的版本、位置、比例和修订号");
        }
        return db.keyed(owner, "BOOKMARK", key, new Save(id, command), Bookmark.class,
                () -> save(owner, documents.find(id), command));
    }

    private Bookmark save(String owner, Documents.Document doc, Command input) {
        db.lock(db.write(List.of("bookmark", owner, doc.id())));
        var stored = current(owner, doc.id());
        int revision = stored.map(Row::revision).orElse(0);
        if (revision != input.revision() || !input.version().equals(doc.version())) {
            throw Problem.conflict("书签或资料版本已更新", restore(doc, stored));
        }
        var units = documents.sections(doc, input.version());
        int index = units.stream().map(SourceFiles.Unit::anchor).toList().indexOf(input.anchor());
        if (index == -1) {
            throw Problem.invalid("阅读位置超出原文范围");
        }
        var location = Passages.location(units, index);
        db.update("""
                insert into bookmarks values (?,?,?,?,?,?,?,?)
                on conflict (owner,document_id) do update set version=excluded.version,anchor=excluded.anchor,
                    ratio=excluded.ratio,excerpt=excluded.excerpt,context=excluded.context,revision=excluded.revision
                """, owner, doc.id(), input.version(), db.write(location.anchor()), input.ratio(), location.excerpt(),
                location.context(), revision + 1);
        return new Bookmark(doc.version(), location.anchor(), input.ratio(), revision + 1, null);
    }

    private Bookmark restore(Documents.Document doc, Optional<Row> saved) {
        if (doc.status().equals("DELETED")) {
            throw Problem.missing("资料已删除");
        }
        if (doc.version() == null) {
            throw Problem.unavailable("原文暂时无法读取");
        }
        var start = doc.kind().equals("PDF") ? new SourceFiles.Anchor(1, null, null)
                : new SourceFiles.Anchor(null, null, 1);
        if (saved.isEmpty()) {
            return new Bookmark(doc.version(), start, 0, 0, null);
        }
        var row = saved.get();
        if (row.version().equals(doc.version())) {
            return new Bookmark(doc.version(), db.read(row.anchor(), SourceFiles.Anchor.class), row.ratio(), row.revision(), null);
        }
        var match = Passages.locate(documents.sections(doc, doc.version()), row.excerpt(), row.context());
        if (match.anchor() == null) {
            return new Bookmark(doc.version(), start, 0, row.revision(), "资料已更新，原位置无法确定，请重新选择");
        }
        return new Bookmark(doc.version(), match.anchor(), row.ratio(), row.revision(), "资料已更新，已定位到当前版本");
    }

    private Optional<Row> current(String owner, String id) {
        return db.one(Row.class, """
                select version,anchor,ratio,excerpt,context,revision from bookmarks where owner=? and document_id=?
                """, owner, id);
    }

    private record Save(String documentId, Command command) {}
    public record Command(String version, SourceFiles.Anchor anchor, Double ratio, Integer revision) {}
    public record Bookmark(String version, SourceFiles.Anchor anchor, double ratio, int revision, String notice) {}
    public record Row(String version, String anchor, double ratio, String excerpt, String context, int revision) {}
}
