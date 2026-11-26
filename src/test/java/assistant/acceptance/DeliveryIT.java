package assistant.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.Multipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static assistant.acceptance.Acceptance.id;
import static assistant.acceptance.Acceptance.state;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_DB_URL", matches = ".+")
class DeliveryIT {
    @TempDir Path workspace;

    @Test void sendFinalVersion() throws Exception {
        try (var host = new Acceptance(workspace); var clicks = Executors.newFixedThreadPool(2)) {
            var desktop = host.api;
            var phone = host.signIn();
            host.incoming("算法讨论", "请用 Java 解释。", null);
            desktop.done(desktop.submit("/mail/sync", null));
            var thread = desktop.get("/threads").get(0);
            String threadId = id(thread);
            String message = thread.path("latestMessageId").asText();
            String draft = "/drafts/" + id(desktop.draft(threadId, message));
            assertThat(phone.get(draft).path("revision").asInt()).isEqualTo(1);
            var edited = DraftIT.edited("这是确认的最终正文。", List.of(Map.of("name", "example.txt", "base64", "AQID")));
            desktop.request("PATCH", draft, Map.of("revision", 1, "content", edited), "edit-final", 200);
            phone.request("POST", draft + "/send", Map.of("revision", 1), null, 409);
            assertThat(host.outgoing()).isEmpty();
            var first = clicks.submit(() -> desktop.request("POST", draft + "/send", Map.of("revision", 2), null, 202));
            var second = clicks.submit(() -> phone.request("POST", draft + "/send", Map.of("revision", 2), null, 202));
            String send = id(first.get());
            assertThat(id(second.get())).isEqualTo(send);
            desktop.waitFor("/sends/" + send, node -> state(node).equals("SENT"));
            assertThat(host.outgoing()).hasSize(1);
            var accepted = host.outgoing().getFirst();
            assertThat(accepted.getSubject()).isEqualTo("Re: Java");
            var multipart = (Multipart) accepted.getContent();
            assertThat(multipart.getBodyPart(0).getContent().toString().strip()).isEqualTo("这是确认的最终正文。");
            assertThat(multipart.getBodyPart(1).getFileName()).isEqualTo("example.txt");
            assertThat(multipart.getBodyPart(1).getInputStream().readAllBytes()).containsExactly(1, 2, 3);

            String uncertain = "/drafts/" + id(desktop.draft(threadId, message));
            host.sender().loseReceipt.set(true);
            String pending = id(desktop.request("POST", uncertain + "/send", Map.of("revision", 1), null, 202));
            desktop.waitFor("/sends/" + pending, node -> state(node).equals("VERIFY"));
            assertThat(host.outgoing()).hasSize(2);
            host.restart();
            var recovered = host.signIn();
            assertThat(state(recovered.get("/sends/" + pending))).isEqualTo("VERIFY");
            JsonNode repeated = recovered.request("POST", uncertain + "/send", Map.of("revision", 1), null, 202);
            assertThat(id(repeated)).isEqualTo(pending);
            assertThat(host.outgoing()).hasSize(2);
        }
    }
}
