package assistant.knowledge;

import assistant.common.Problem;
import assistant.common.Store;
import assistant.model.Intelligence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.StringJoiner;
import java.time.Duration;

@Service
public class Retrieval {
    private static final Logger LOG = LoggerFactory.getLogger(Retrieval.class);
    private final Store db;
    private final Intelligence model;
    private final IndexProfile profile;

    Retrieval(Store db, Intelligence model, IndexProfile profile) {
        this.db = db;
        this.model = model;
        this.profile = profile;
    }

    /** Fuses keyword and vector results from the current index. docs/api/knowledge.md#retrieval */
    public List<Evidence> search(String text, int limit) {
        if (text == null || text.isBlank() || limit < 1) {
            throw Problem.invalid("检索需要非空问题和正数上限");
        }
        long started = System.nanoTime();
        String active = profile.active();
        if (active == null && !db.one(Boolean.class, "select exists(select 1 from documents where status<>'DELETED')").orElse(false)) {
            return List.of();
        }
        if (!profile.id.equals(active)) {
            throw Problem.unavailable("资料索引尚未就绪，请同步资料后重试");
        }
        if (!db.one(Boolean.class, "select exists(select 1 from fragments where profile=?)", active).orElse(false)) {
            return List.of();
        }
        var embeddings = model.embed(List.of(text));
        if (embeddings.size() != 1) {
            throw Problem.unavailable("嵌入模型没有返回查询向量");
        }
        int count = Math.max(25, limit);
        var result = db.list(Row.class, """
                with current_fragments as (
                    select f.* from fragments f join documents d on d.id=f.document_id and d.version=f.version
                    join index_settings s on s.singleton and s.profile=f.profile
                    where f.profile=? and d.status='READY'
                ), lexical_scores as (
                    select document_id,ordinal,
                        (select count(*) from unnest(string_to_array(terms,' ')) as t(term)
                         where term=any(string_to_array(?,' '))) as score
                    from current_fragments
                ), lexical as (
                    select document_id,ordinal,row_number() over(order by score desc,document_id,ordinal) as rank
                    from lexical_scores where score>0 order by rank limit ?
                ), semantic_scores as (
                    select document_id,ordinal,embedding <=> cast(? as vector) as distance from current_fragments
                ), semantic as (
                    select document_id,ordinal,row_number() over(order by distance,document_id,ordinal) as rank
                    from semantic_scores where distance<=0.72 order by rank limit ?
                ), rank_parts as (
                    select document_id,ordinal,1.0/(60+rank) as score from lexical
                    union all select document_id,ordinal,1.0/(60+rank) from semantic
                ), ranks as (select document_id,ordinal,sum(score) as score from rank_parts group by document_id,ordinal)
                select f.document_id,f.version,f.ordinal,f.anchor,f.excerpt,f.context
                from current_fragments f join ranks r using (document_id,ordinal)
                order by r.score desc,f.document_id,f.ordinal limit ?
                """, active, Passages.terms(text), count, vector(embeddings.getFirst()), count, limit)
                .stream().map(row -> new Evidence(row.documentId(), row.version(), row.ordinal(),
                        db.read(row.anchor(), SourceFiles.Anchor.class), row.excerpt(), row.context())).toList();
        LOG.info("资料检索耗时 {} ms，返回 {} 段", Duration.ofNanos(System.nanoTime() - started).toMillis(), result.size());
        return result;
    }

    static String vector(float[] values) {
        if (values == null || values.length == 0) {
            throw Problem.unavailable("嵌入模型返回空向量");
        }
        var result = new StringJoiner(",", "[", "]");
        boolean nonzero = false;
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw Problem.unavailable("嵌入模型返回无效向量");
            }
            nonzero |= value != 0;
            result.add(Float.toString(value));
        }
        if (!nonzero) {
            throw Problem.unavailable("嵌入模型返回零向量");
        }
        return result.toString();
    }

    public record Evidence(String documentId, String version, int fragment, SourceFiles.Anchor anchor,
                           String excerpt, String context) {}
    public record Row(String documentId, String version, int ordinal, String anchor, String excerpt, String context) {}
}
