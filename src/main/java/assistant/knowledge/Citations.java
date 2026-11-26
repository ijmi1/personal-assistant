package assistant.knowledge;

import assistant.common.Problem;
import assistant.common.Store;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

@Service
public class Citations {
    private final Store db;

    public Citations(Store db) { this.db = db; }

    public Record get(String id) {
        return db.one(Record.class, "select * from citations where id=?", id)
                .orElseThrow(() -> Problem.missing("引用不存在"));
    }

    public List<String> save(List<Retrieval.Evidence> evidence, List<Integer> numbers) {
        var ids = new LinkedHashSet<Integer>(numbers);
        return ids.stream().filter(number -> number != null && number >= 1 && number <= evidence.size())
                .map(number -> save(evidence.get(number - 1))).toList();
    }

    private String save(Retrieval.Evidence evidence) {
        String id = Store.id();
        db.update("insert into citations values (?,?,?,?,?,?)", id, evidence.documentId(), evidence.version(),
                db.write(evidence.anchor()), evidence.excerpt(), evidence.context());
        return id;
    }

    public record Record(String id, String documentId, String version, String anchor, String excerpt, String context) {}
}
