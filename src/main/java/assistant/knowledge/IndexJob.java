package assistant.knowledge;

import assistant.common.Problem;
import assistant.common.Store;
import assistant.model.Intelligence;
import assistant.task.Jobs;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
class IndexJob implements Jobs.Handler {
    private final Store db;
    private final SourceFiles files;
    private final Intelligence model;
    private final IndexProfile profile;

    IndexJob(Store db, SourceFiles files, Intelligence model, IndexProfile profile) {
        this.db = db;
        this.files = files;
        this.model = model;
        this.profile = profile;
    }

    @Override public String type() { return "INDEX"; }

    @Override
    public Supplier<Jobs.Result> prepare(Jobs.Progress progress, String input) {
        progress.step("扫描资料目录");
        var discovered = files.scan();
        List<Work> work = db.tx(() -> discover(progress, discovered));
        boolean retry = false;
        for (int i = 0; i < work.size(); i++) {
            progress.step("建立索引 " + (i + 1) + "/" + work.size());
            retry |= !index(progress, work.get(i));
        }
        if (retry) {
            throw Problem.unavailable("部分资料索引未完成，请查看资料状态并重新同步");
        }
        return () -> {
            db.update("""
                    insert into index_settings (singleton, profile) values (true,?)
                    on conflict (singleton) do update set profile=excluded.profile
                    """, profile.id);
            db.update("""
                    delete from fragments f using documents d where f.document_id=d.id
                    and (f.profile<>? or d.status<>'READY' or f.version<>d.version)
                    """, profile.id);
            return new Jobs.Result("documents", null);
        };
    }

    private List<Work> discover(Jobs.Progress progress, List<SourceFiles.Discovered> found) {
        requireCurrent(progress);
        var existing = db.list(Indexed.class, """
                select id,path,version,indexed_profile,status from documents where status<>'DELETED'
                """);
        Map<String, Indexed> paths = existing.stream().collect(Collectors.toMap(Indexed::path, row -> row));
        var foundPaths = found.stream().map(SourceFiles.Discovered::path).collect(Collectors.toSet());
        var removed = existing.stream().filter(doc -> !foundPaths.contains(doc.path())).toList();
        var absentByHash = removed.stream().filter(doc -> doc.version() != null)
                .collect(Collectors.groupingBy(Indexed::version));
        var foundCount = found.stream().filter(file -> file.version() != null)
                .collect(Collectors.groupingBy(SourceFiles.Discovered::version, Collectors.counting()));
        List<Work> work = new ArrayList<>();
        for (var file : found) {
            var old = paths.get(file.path());
            var candidates = file.version() == null ? List.<Indexed>of()
                    : absentByHash.getOrDefault(file.version(), List.of());
            if (old == null && candidates.size() == 1 && foundCount.get(file.version()) == 1L) {
                old = candidates.getFirst();
                db.update("update documents set path=?,kind=?,updated_at=now() where id=?", file.path(), file.kind(), old.id());
                paths.put(file.path(), old);
            }
            Work candidate = register(file, old);
            if (candidate != null) {
                work.add(candidate);
            }
        }
        var retained = paths.entrySet().stream().filter(entry -> foundPaths.contains(entry.getKey()))
                .map(entry -> entry.getValue().id()).collect(Collectors.toSet());
        for (var doc : removed) {
            if (!retained.contains(doc.id())) {
                db.update("update documents set status='DELETED',reason='资料已删除',updated_at=now() where id=?", doc.id());
            }
        }
        return work;
    }

    private Work register(SourceFiles.Discovered file, Indexed old) {
        String id = old == null ? Store.id() : old.id();
        if (old != null && Objects.equals(file.version(), old.version()) && profile.id.equals(old.indexedProfile())) {
            return null;
        }
        String status = file.version() == null ? "FAILED" : "INDEXING";
        db.update("""
                insert into documents (id,path,kind,version,status,reason) values (?,?,?,?,?,?)
                on conflict (id) do update set version=excluded.version,status=excluded.status,
                    reason=excluded.reason,indexed_profile=null,updated_at=now()
                """, id, file.path(), file.kind(), file.version(), status, file.reason());
        return file.version() == null ? null : new Work(id, file.path(), file.version());
    }

    private boolean index(Jobs.Progress progress, Work work) {
        List<Passages.Passage> passages;
        try {
            var snapshot = files.read(work.path());
            if (!snapshot.version().equals(work.version())) {
                status(progress, work, "INDEXING", "原文正在变化，请重新同步", null);
                return false;
            }
            passages = Passages.split(files.sections(snapshot));
        } catch (IOException | Problem e) {
            status(progress, work, "FAILED", "无法提取原文内容", profile.id);
            return true;
        }
        if (passages.isEmpty()) {
            status(progress, work, "UNSEARCHABLE", "未提取到文字，可打开原文阅读", profile.id);
            return true;
        }
        List<float[]> embeddings;
        try {
            embeddings = embed(passages);
        } catch (Problem e) {
            status(progress, work, "FAILED", e.getMessage(), null);
            return false;
        }
        if (!stillCurrent(work)) {
            status(progress, work, "INDEXING", "原文正在变化，请重新同步", null);
            return false;
        }
        db.atomic(() -> publish(progress, work, passages, embeddings));
        return true;
    }

    private boolean stillCurrent(Work work) {
        try {
            return files.read(work.path()).version().equals(work.version());
        } catch (IOException | Problem e) {
            return false;
        }
    }

    private List<float[]> embed(List<Passages.Passage> passages) {
        List<float[]> vectors = new ArrayList<>(passages.size());
        for (int start = 0; start < passages.size(); start += 32) {
            var batch = passages.subList(start, Math.min(passages.size(), start + 32));
            var embedded = model.embed(batch.stream().map(Passages.Passage::excerpt).toList());
            if (embedded.size() != batch.size()) {
                throw Problem.unavailable("嵌入模型返回的片段数量不符");
            }
            embedded.forEach(Retrieval::vector);
            vectors.addAll(embedded);
        }
        return vectors;
    }

    private void publish(Jobs.Progress progress, Work work, List<Passages.Passage> passages, List<float[]> vectors) {
        requireCurrent(progress);
        if (setStatus(work, "READY", null, profile.id) != 1) {
            return;
        }
        db.update("delete from fragments where document_id=? and version=? and profile=?", work.id(), work.version(), profile.id);
        for (int i = 0; i < passages.size(); i++) {
            var passage = passages.get(i);
            db.update("""
                    insert into fragments values (?,?,?,?,?,?,?,?,cast(? as vector))
                    """, work.id(), work.version(), profile.id, i, db.write(passage.anchor()), passage.excerpt(),
                    passage.context(), Passages.terms(passage.excerpt()), Retrieval.vector(vectors.get(i)));
        }
    }

    private void status(Jobs.Progress progress, Work work, String status, String reason, String indexedProfile) {
        db.atomic(() -> {
            requireCurrent(progress);
            setStatus(work, status, reason, indexedProfile);
        });
    }

    private int setStatus(Work work, String status, String reason, String indexedProfile) {
        return db.update("""
                update documents set status=?,reason=?,indexed_profile=?,updated_at=now()
                where id=? and version=? and path=? and status='INDEXING'
                """, status, reason, indexedProfile, work.id(), work.version(), work.path());
    }

    private static void requireCurrent(Jobs.Progress progress) {
        if (!progress.ownsRun()) {
            throw Problem.conflict("索引任务已由其他执行接手");
        }
    }

    public record Indexed(String id, String path, String version, String indexedProfile, String status) {}
    private record Work(String id, String path, String version) {}
}
