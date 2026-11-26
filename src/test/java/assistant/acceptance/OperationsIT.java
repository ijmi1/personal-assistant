package assistant.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static assistant.acceptance.Acceptance.JSON;
import static assistant.acceptance.Acceptance.state;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_DB_URL", matches = ".+")
class OperationsIT {
    @TempDir Path workspace;

    @Test void authenticatesBothApiVersionsAndEnforcesSharedQuota() throws Exception {
        try (var host = new Acceptance(workspace, Map.of("assistant.jobs.ai-per-minute", "1"))) {
            var legacy = host.api;
            var versioned = new Acceptance.Api(host.address(), "/api/v1");
            versioned.request("GET", "/tasks", null, null, 401);
            versioned.login();
            assertThat(legacy.get("/session")).isEqualTo(versioned.get("/session"));
            var question = Map.of("question", "uncovered");
            var accepted = versioned.request("POST", "/questions", question, "same", 202);
            assertThat(legacy.request("POST", "/questions", question, "same", 202)).isEqualTo(accepted);

            var rejected = legacy.raw("POST", "/questions", JSON.writeValueAsString(question),
                    "application/json", "second", true);
            assertThat(rejected.statusCode()).isEqualTo(429);
            assertThat(rejected.headers().firstValue("Retry-After")).contains("60");
            assertThat(versioned.get("/tasks")).hasSize(1);
            versioned.done(accepted.path("taskId").asText());
            host.sql().update("update tasks set created_at=now()-interval '61 seconds'");
            versioned.request("POST", "/questions", question, "second", 202);
        }
    }

    @Test void exposesReadinessAndRunsAutomaticSyncAndMailJobs() throws Exception {
        try (var host = new Acceptance(workspace, Map.of(
                "assistant.schedule.index-ms", "2000", "assistant.schedule.mail-ms", "2000"))) {
            host.write("automatic.md", Acceptance.NOTE);
            host.incoming("Automatic sync", "fixture", null);
            var anonymous = new Acceptance.Api(host.address());
            var health = anonymous.web("/actuator/health/readiness");
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(health.body()).path("status").asText()).isEqualTo("UP");
            assertThat(anonymous.web("/actuator/metrics").statusCode()).isEqualTo(401);
            assertThat(host.api.web("/actuator/metrics/assistant.tasks.queued").statusCode()).isEqualTo(200);
            assertThat(host.api.web("/actuator/metrics/http.server.requests").statusCode()).isEqualTo(200);
            assertThat(host.api.web("/api/v1/missing").statusCode()).isEqualTo(404);

            host.api.waitFor("/documents", docs -> !docs.isEmpty() && state(docs.get(0)).equals("READY"));
            var threads = host.api.waitFor("/threads", list -> !list.isEmpty());
            assertThat(threads.get(0).path("subject").asText()).isEqualTo("Automatic sync");
            assertThat(host.api.get("/tasks")).allSatisfy(task ->
                    assertThat(task.path("type").asText()).isIn("MAIL", "INDEX"));
            assertThat(host.outgoing()).isEmpty();
        }
    }
}
