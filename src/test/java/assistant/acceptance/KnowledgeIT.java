package assistant.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import static assistant.acceptance.Acceptance.NOTE;
import static assistant.acceptance.Acceptance.id;
import static assistant.acceptance.Acceptance.state;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_DB_URL", matches = ".+")
class KnowledgeIT {
    @TempDir Path workspace;

    @Test void syncChanges() throws Exception {
        try (var host = new Acceptance(workspace)) {
            var api = host.api;
            host.write("counter.md", "LegacyCounter is the original implementation.");
            host.write("invalid.pdf", "not a PDF");
            Acceptance.pdf(host.documents.resolve("scan.pdf"), "");
            String key = "scan-initial";
            var accepted = api.request("POST", "/index/sync", null, key, 202);
            assertThat(api.request("POST", "/index/sync", null, key, 202)).isEqualTo(accepted);
            api.done(accepted.path("taskId").asText());
            var listed = api.get("/documents");
            assertThat(listed).hasSize(3);
            assertThat(state(listed.get(0))).isEqualTo("READY");
            assertThat(state(listed.get(1))).isEqualTo("FAILED");
            assertThat(state(listed.get(2))).isEqualTo("UNSEARCHABLE");
            String document = id(listed.get(0));
            assertThat(api.answer("LegacyCounter").toString()).contains("original implementation");

            host.write("counter.md", "FreshCounter uses the revised implementation.");
            api.done(api.submit("/index/sync", null));
            assertThat(api.answer("FreshCounter").toString()).contains("revised implementation");
            assertThat(api.answer("LegacyCounter").path("claims")).isEmpty();

            Files.move(host.documents.resolve("counter.md"), host.documents.resolve("renamed.md"));
            api.done(api.submit("/index/sync", null));
            var renamed = api.get("/documents");
            assertThat(renamed).hasSize(3);
            assertThat(id(renamed.get(1))).isEqualTo(document);
            assertThat(renamed.get(1).path("name").asText()).isEqualTo("renamed.md");

            Files.delete(host.documents.resolve("renamed.md"));
            api.done(api.submit("/index/sync", null));
            assertThat(api.get("/documents")).hasSize(2);
            assertThat(api.answer("FreshCounter").path("gap").asBoolean()).isTrue();

            host.write("changing.md", "LegacyCounter was edited during embedding.");
            host.model().beforeNextEmbedding.set(() -> {
                try {
                    Files.writeString(host.documents.resolve("changing.md"), "FreshCounter is ready to publish.");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            String obsolete = api.submit("/index/sync", null);
            api.waitFor("/tasks/" + obsolete, node -> state(node).equals("FAILED"));
            assertThat(api.answer("LegacyCounter").path("gap").asBoolean()).isTrue();
            api.done(api.submit("/index/sync", null));
            assertThat(api.answer("FreshCounter").toString()).contains("ready to publish");

            Files.move(host.documents, workspace.resolve("offline"));
            String unavailable = api.submit("/index/sync", null);
            assertThat(api.waitFor("/tasks/" + unavailable, node -> state(node).equals("FAILED")).path("error").asText())
                    .contains("资料目录不可达");
            assertThat(api.get("/documents")).hasSize(3);
        }
    }

    @Test void answerFromSources() throws Exception {
        try (var host = new Acceptance(workspace)) {
            var api = host.api;
            host.write("算法.md", "# Java\n\n" + NOTE);
            api.done(api.submit("/index/sync", null));
            for (String question : new String[]{"滑动窗口为什么要用 while？", "为什么必须反复缩小？", "while"}) {
                var answer = api.answer(question);
                assertThat(answer.path("claims")).hasSize(1);
                var claim = answer.path("claims").get(0);
                assertThat(claim.path("text").asText()).isEqualTo(NOTE);
                String citation = claim.path("citationIds").get(0).asText();
                assertThat(api.get("/citations/" + citation).path("excerpt").asText()).isEqualTo(NOTE);
            }
            assertThat(api.answer("uncovered topic").path("gap").asBoolean()).isTrue();
            String key = "same-question";
            var request = Map.of("question", "why while?");
            var accepted = api.request("POST", "/questions", request, key, 202);
            assertThat(api.request("POST", "/questions", request, key, 202)).isEqualTo(accepted);
            api.request("POST", "/questions", Map.of("question", "other"), key, 409);
        }
    }
}
