package assistant.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static assistant.acceptance.Acceptance.id;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_DB_URL", matches = ".+")
class MailboxIT {
    @TempDir Path workspace;

    @Test void syncThread() throws Exception {
        try (var host = new Acceptance(workspace)) {
            String first = host.incoming("算法讨论", "用 Java。", null);
            host.incoming("Re: 算法讨论", "滑动窗口为什么要用 while？", first);
            host.incoming("算法讨论", "这是另一封邮件。", null);
            var api = host.api;
            api.done(api.submit("/mail/sync", null));
            api.done(api.submit("/mail/sync", null));
            var threads = api.get("/threads");
            assertThat(threads).hasSize(2);
            var paired = threads.get(1);
            var history = api.get("/threads/" + id(paired) + "?through=" + paired.path("latestMessageId").asText());
            assertThat(history.path("messages")).hasSize(2);
            assertThat(history.path("messages").get(0).path("body").asText()).contains("Java");

            host.sql().update("update mail_cursor set uid_validity=uid_validity+1");
            host.sql().update("update mail_deliveries set uid_validity=uid_validity+1");
            api.done(api.submit("/mail/sync", null));
            assertThat(api.get("/threads")).isEqualTo(threads);
            assertThat(api.get("/tasks")).allSatisfy(task -> assertThat(task.path("type").asText()).isEqualTo("MAIL"));
            assertThat(host.outgoing()).isEmpty();
        }
    }
}
