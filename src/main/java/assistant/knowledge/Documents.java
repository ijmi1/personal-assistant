package assistant.knowledge;

import assistant.common.Problem;
import assistant.common.Store;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class Documents {
    private final Store db;
    private final SourceFiles files;

    public Documents(Store db, SourceFiles files) {
        this.db = db;
        this.files = files;
    }

    public List<View> list() {
        return db.list(Document.class, "select * from documents where status<>'DELETED' order by path")
                .stream().map(Document::view).toList();
    }

    public Document find(String id) {
        return db.one(Document.class, "select * from documents where id=?", id)
                .orElseThrow(() -> Problem.missing("资料不存在"));
    }

    public SourceFiles.Snapshot content(Document doc, String version) {
        if (doc.status().equals("DELETED")) {
            throw Problem.missing("资料已删除");
        }
        try {
            var snapshot = files.read(doc.path());
            if (!snapshot.version().equals(version)) {
                throw Problem.conflict("原文已变化，请重新打开引用或同步资料");
            }
            return snapshot;
        } catch (NoSuchFileException e) {
            throw Problem.missing("原文不存在，可能已改名或删除");
        } catch (IOException e) {
            throw Problem.unavailable("原文暂时无法读取");
        }
    }

    public List<SourceFiles.Unit> outline(String id, String version) {
        Document doc = find(id);
        if (!doc.kind().equals("MARKDOWN")) {
            throw Problem.invalid("只有 Markdown 资料有区块目录");
        }
        return sections(doc, version);
    }

    public List<SourceFiles.Unit> sections(Document doc, String version) {
        try {
            return files.sections(content(doc, version));
        } catch (IOException e) {
            throw Problem.unavailable("无法解析原文");
        }
    }

    public record Document(String id, String path, String kind, String version, String status, String reason,
                           OffsetDateTime updatedAt) {
        public View view() {
            return new View(id, Path.of(path).getFileName().toString(), kind, version, status, reason, updatedAt);
        }
    }
    public record View(String id, String name, String kind, String version, String status, String reason,
                       OffsetDateTime updatedAt) {}
}
