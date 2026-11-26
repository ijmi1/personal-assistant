package assistant.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static assistant.acceptance.Acceptance.NOTE;
import static assistant.acceptance.Acceptance.id;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_DB_URL", matches = ".+")
class ReaderIT {
    @TempDir Path workspace;

    @Test void followCitation() throws Exception {
        try (var host = new Acceptance(workspace)) {
            var api = host.api;
            host.write("note.md", "# Java\n\n" + NOTE);
            Acceptance.pdf(host.documents.resolve("book.pdf"), "Intro", "Background", "needle PDF exact page");
            api.done(api.submit("/index/sync", null));
            String md = citation(api.answer("while"));
            assertThat(api.get(md).path("anchor").path("paragraph").asInt()).isEqualTo(2);
            assertThat(api.get(citation(api.answer("needle PDF"))).path("anchor").path("page").asInt()).isEqualTo(3);

            host.write("note.md", "# 算法\n\n导言。\n\n## Java\n\n" + NOTE);
            assertThat(api.get(md).path("reason").asText()).contains("变化");
            api.done(api.submit("/index/sync", null));
            var relocated = api.get(md);
            assertThat(relocated.path("changed").asBoolean()).isTrue();
            assertThat(relocated.path("anchor").path("paragraph").asInt()).isEqualTo(4);

            host.write("note.md", "# 甲\n\n" + NOTE + "\n\n# 乙\n\n" + NOTE);
            api.done(api.submit("/index/sync", null));
            assertThat(api.get(md).path("reason").asText()).contains("多处匹配");
            Files.delete(host.documents.resolve("note.md"));
            api.done(api.submit("/index/sync", null));
            assertThat(api.get(md).path("reason").asText()).contains("已删除");
        }
    }

    @Test void resumeAcrossDevices() throws Exception {
        try (var host = new Acceptance(workspace)) {
            var desktop = host.api;
            var phone = host.signIn();
            host.write("note.md", "# Java\n\n" + NOTE);
            Acceptance.pdf(host.documents.resolve("book.pdf"), "one", "two", "needle PDF", "four",
                    "five", "six", "seven", "eight", "nine");
            desktop.done(desktop.submit("/index/sync", null));
            var docs = desktop.get("/documents");
            String pdf = "/documents/" + id(docs.get(0)) + "/bookmark";
            var saved = desktop.request("PUT", pdf, position(docs.get(0), Map.of("page", 8), 0.5, 0), "pdf-first", 200);
            phone.get(citation(phone.answer("needle PDF")));
            assertThat(phone.get(pdf)).isEqualTo(saved);
            var conflict = desktop.request("PUT", pdf, position(docs.get(0), Map.of("page", 2), 0, 0), "late", 409);
            assertThat(conflict.path("current")).isEqualTo(saved);
            assertThat(desktop.request("PUT", pdf, position(docs.get(0), Map.of("page", 8), 0.5, 0), "pdf-first", 200))
                    .isEqualTo(saved);

            String markdown = "/documents/" + id(docs.get(1)) + "/bookmark";
            var mdCitation = desktop.get(citation(desktop.answer("while")));
            var md = desktop.request("PUT", markdown, position(docs.get(1), mdCitation.path("anchor"), 0.35, 0), "md-first", 200);
            phone.get(citation(phone.answer("while")));
            assertThat(phone.get(markdown)).isEqualTo(md);
        }
    }

    private static Map<String, Object> position(JsonNode doc, Object anchor, double ratio, int revision) {
        return Map.of("version", doc.path("version").asText(), "anchor", anchor, "ratio", ratio, "revision", revision);
    }

    private static String citation(JsonNode answer) {
        return "/citations/" + answer.path("claims").get(0).path("citationIds").get(0).asText();
    }
}
