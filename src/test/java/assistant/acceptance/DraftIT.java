package assistant.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static assistant.acceptance.Acceptance.NOTE;
import static assistant.acceptance.Acceptance.id;
import static assistant.acceptance.Acceptance.state;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_DB_URL", matches = ".+")
class DraftIT {
    @TempDir Path workspace;

    @Test void editAndKeep() throws Exception {
        try (var host = new Acceptance(workspace)) {
            var desktop = host.api;
            var phone = host.signIn();
            host.write("notes.md", NOTE);
            desktop.done(desktop.submit("/index/sync", null));
            String parent = host.incoming("Java 滑动窗口", "用 Java。", null);
            String second = host.incoming("Re: Java 滑动窗口", "窗口为什么用 while？", parent);
            desktop.done(desktop.submit("/mail/sync", null));
            var thread = desktop.get("/threads").get(0);
            String path = "/threads/" + id(thread) + "/drafts";
            var generate = Map.of("messageId", thread.path("latestMessageId").asText());
            var first = phone.request("POST", path, generate, "draft-double", 202);
            assertThat(phone.request("POST", path, generate, "draft-double", 202)).isEqualTo(first);
            String draftPath = "/drafts/" + desktop.done(first.path("taskId").asText()).path("resultId").asText();
            var draft = desktop.get(draftPath);
            assertThat(draft.path("content").path("body").asText()).contains("Java");
            assertThat(draft.path("citationIds")).hasSize(1);

            var updated = phone.request("PATCH", draftPath, Map.of("revision", 1, "content", edited("修改后的示例", List.of())),
                    "edit-from-phone", 200);
            assertThat(updated.path("revision").asInt()).isEqualTo(2);
            host.incoming("Re: Java 滑动窗口", "这里追加一个问题。", second);
            desktop.done(desktop.submit("/mail/sync", null));
            assertThat(desktop.get(draftPath).path("newIncoming").asBoolean()).isTrue();
            assertThat(desktop.get(draftPath).path("content").path("body").asText()).isEqualTo("修改后的示例");
            assertThat(desktop.request("PATCH", draftPath, Map.of("revision", 1, "content", edited("旧稿", List.of())),
                    "stale-edit", 409).path("current").path("revision").asInt()).isEqualTo(2);
            assertThat(host.outgoing()).isEmpty();
            var discarded = desktop.request("DELETE", draftPath, Map.of("revision", 2), "discard", 200);
            assertThat(state(discarded)).isEqualTo("DISCARDED");
            assertThat(phone.request("DELETE", draftPath, Map.of("revision", 2), "discard", 200)).isEqualTo(discarded);
            assertThat(host.outgoing()).isEmpty();
        }
    }

    static Map<String, Object> edited(String body, List<Map<String, Object>> attachments) {
        return Map.of("to", List.of(Acceptance.CLASSMATE), "cc", List.of(), "bcc", List.of(), "subject", "Re: Java",
                "body", body, "attachments", attachments);
    }
}
