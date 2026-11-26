package assistant.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static assistant.acceptance.Acceptance.state;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_DB_URL", matches = ".+")
class TaskIT {
    @TempDir Path workspace;

    @Test void resumeAfterRestart() throws Exception {
        try (var host = new Acceptance(workspace)) {
            var anonymous = new Acceptance.Api(host.address());
            anonymous.request("GET", "/tasks", null, null, 401);
            var unauthenticatedWrite = host.api.raw("POST", "/questions", "{\"question\":\"while\"}",
                    "application/json", "missing-token", false);
            assertThat(unauthenticatedWrite.statusCode()).isEqualTo(403);

            host.stopWorker();
            String task = host.api.submit("/questions", Map.of("question", "uncovered"));
            assertThat(state(host.api.get("/tasks/" + task))).isEqualTo("QUEUED");
            host.sql().update("update tasks set status='RUNNING',attempts=1 where id=?", task);
            host.sql().update("""
                    update scheduled_tasks set picked=true,picked_by='interrupted',
                    last_heartbeat=now()-interval '5 minutes' where task_instance=?
                    """, task);
            host.restart();
            var phone = host.signIn();
            var restored = phone.done(task);
            assertThat(restored.path("resultType").asText()).isEqualTo("answer");
            assertThat(phone.get("/answers/" + restored.path("resultId").asText()).path("gap").asBoolean()).isTrue();
            assertThat(phone.get("/tasks")).hasSize(1);
        }
    }
}
