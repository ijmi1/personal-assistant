package assistant.acceptance;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static assistant.acceptance.Acceptance.JSON;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_DB_URL", matches = ".+")
class HardeningIT {
    @TempDir Path workspace;

    @Test void protectsApiDocumentationAndAllowsOnlyConfiguredCorsOrigins() throws Exception {
        try (var host = new Acceptance(workspace, Map.of("assistant.cors-origins", "https://web.example.test"))) {
            var anonymous = new Acceptance.Api(host.address(), "/api/v1");
            assertThat(anonymous.web("/v3/api-docs").statusCode()).isEqualTo(401);
            var schema = host.api.web("/v3/api-docs");
            assertThat(schema.statusCode()).isEqualTo(200);
            var openApi = JSON.readTree(schema.body());
            var paths = openApi.path("paths");
            var question = paths.path("/api/v1/questions").path("post");
            assertThat(question.path("summary").asText()).isEqualTo("提交资料问题");
            assertThat(question.path("tags")).extracting(JsonNode::asText).contains("资料与阅读");
            assertThat(question.path("parameters")).anySatisfy(parameter -> {
                assertThat(parameter.path("name").asText()).isEqualTo("Idempotency-Key");
                assertThat(parameter.path("in").asText()).isEqualTo("header");
                assertThat(parameter.path("description").asText()).isNotBlank();
                assertThat(parameter.path("required").asBoolean()).isTrue();
            });
            assertThat(question.path("responses").has("202")).isTrue();
            var history = paths.path("/api/v1/threads/{id}").path("get");
            assertThat(history.path("parameters")).anySatisfy(parameter -> {
                assertThat(parameter.path("name").asText()).isEqualTo("through");
                assertThat(parameter.path("description").asText()).contains("省略");
                assertThat(parameter.path("required").asBoolean()).isFalse();
            });
            assertResponseSchema(openApi, paths.path("/api/v1/answers/{id}").path("get"), "claims");
            assertResponseSchema(openApi, paths.path("/api/v1/sends/{id}").path("get"), "draftId");
            assertThat(host.api.web("/swagger-ui/index.html").statusCode()).isEqualTo(200);

            var headers = Map.of("Origin", "https://web.example.test", "Access-Control-Request-Method", "PUT",
                    "Access-Control-Request-Headers", "X-CSRF-TOKEN,Content-Type");
            var allowed = anonymous.raw("OPTIONS", "/documents/doc/bookmark", null, null, null, false, headers);
            assertThat(allowed.statusCode()).isEqualTo(200);
            assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("https://web.example.test");
            var denied = anonymous.raw("OPTIONS", "/documents/doc/bookmark", null, null, null, false,
                    Map.of("Origin", "https://other.example.test", "Access-Control-Request-Method", "PUT"));
            assertThat(denied.statusCode()).isEqualTo(403);
            host.api.request("POST", "/client-errors", Map.of("kind", "navigation"), null, 204);
            host.api.request("POST", "/client-errors", Map.of("kind", "unknown"), null, 400);
        }
    }

    private static void assertResponseSchema(JsonNode openApi, JsonNode operation, String field) {
        var mediaType = operation.path("responses").path("200").path("content").elements().next();
        String schemaRef = mediaType.path("schema").path("$ref").asText();
        assertThat(schemaRef).startsWith("#/components/schemas/");
        assertThat(openApi.at(schemaRef.substring(1)).path("properties").has(field)).isTrue();
    }

    @Test void limitsOwnerRequestsAndLoginAttemptsAcrossApiVersions() throws Exception {
        try (var host = new Acceptance(workspace, Map.of("assistant.api-limit.owner-per-minute", "3",
                "assistant.api-limit.login-per-minute", "2", "server.tomcat.remoteip.internal-proxies", "127\\.0\\.0\\.2"))) {
            var versioned = new Acceptance.Api(host.address(), "/api/v1");
            host.api.get("/session");
            assertThat(host.api.web("/api/v1/session").statusCode()).isEqualTo(200);
            var limited = host.api.web("/api/tasks");
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(limited.headers().firstValue("Retry-After")).isPresent();

            assertThat(versioned.raw("POST", "/session/login", null, null, null, false).statusCode()).isEqualTo(403);
            var blocked = versioned.raw("POST", "/session/login", null, null, null, false,
                    Map.of("X-Forwarded-For", "203.0.113.8"));
            assertThat(blocked.statusCode()).isEqualTo(429);
            assertThat(versioned.web("/actuator/health/liveness").statusCode()).isEqualTo(200);
        }
    }

    @Test void restoresTraceContextAndExecutesPreviouslyQueuedTasksAfterRestart() throws Exception {
        String traceId = "0123456789abcdef0123456789abcdef";
        var traceIds = new ConcurrentLinkedQueue<String>();
        var logger = (Logger) LoggerFactory.getLogger("assistant.task.JobRunner");
        var appender = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                String trace = event.getMDCPropertyMap().get("traceId");
                if (trace != null) traceIds.add(trace);
            }
        };
        appender.start();
        try (var host = new Acceptance(workspace, Map.of("management.tracing.sampling.probability", "1"))) {
            host.stopWorker();
            var accepted = host.api.raw("POST", "/questions", "{\"question\":\"uncovered\"}",
                    "application/json", "trace-fixture", true, Map.of("traceparent", "00-" + traceId + "-89abcdef01234567-01"));
            assertThat(accepted.statusCode()).isEqualTo(202);
            String tracked = JSON.readTree(accepted.body()).path("taskId").asText();
            String legacy = host.api.submit("/questions", Map.of("question", "legacy queue item"));
            host.sql().update("update scheduled_tasks set task_data=null where task_instance=?", legacy);
            host.sql().update("update scheduled_tasks set execution_time=now()+interval '5 minutes' where task_instance in (?,?)",
                    tracked, legacy);
            host.restart();
            logger.addAppender(appender);
            host.sql().update("update scheduled_tasks set execution_time=now() where task_instance in (?,?)", tracked, legacy);
            var recovered = host.signIn();
            recovered.done(tracked);
            recovered.done(legacy);
            assertThat(traceIds).contains(traceId);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
