package assistant.acceptance;

import assistant.Assistant;
import assistant.model.Intelligence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

final class Acceptance implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String OWNER = "owner@studio.test";
    static final String CLASSMATE = "classmate@studio.test";
    static final String PASSWORD = "acceptance-only";
    static final String NOTE = "滑动窗口需要 while 反复收缩左边界，直到窗口重新满足约束。";
    final Path documents;
    final GreenMail mail;
    final Api api;
    private final String schema = "it_" + UUID.randomUUID().toString().replace("-", "");
    private ServletWebServerApplicationContext context;

    Acceptance(Path workspace) throws Exception {
        documents = Files.createDirectory(workspace.resolve("documents"));
        mail = new GreenMail(new ServerSetup[]{
                new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP),
                new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP)});
        mail.start();
        mail.setUser(OWNER, "owner", PASSWORD);
        mail.setUser(CLASSMATE, "classmate", PASSWORD);
        try {
            appStart();
            api = signIn();
        } catch (Exception | AssertionError failure) {
            close();
            throw failure;
        }
    }

    private void appStart() {
        Map<String, Object> options = new HashMap<>();
        String url = System.getenv("ASSISTANT_TEST_DB_URL");
        options.put("spring.datasource.url", url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public");
        options.put("spring.datasource.username", System.getenv().getOrDefault("ASSISTANT_TEST_DB_USER", "postgres"));
        options.put("spring.datasource.password", System.getenv().getOrDefault("ASSISTANT_TEST_DB_PASSWORD", "postgres"));
        options.put("spring.flyway.default-schema", schema);
        options.put("spring.flyway.schemas", schema);
        options.put("spring.ai.model.chat", "none");
        options.put("spring.ai.model.embedding", "none");
        options.put("server.port", "0");
        options.put("server.address", "127.0.0.1");
        options.put("server.servlet.session.cookie.secure", "false");
        options.put("assistant.owner", "learner");
        options.put("assistant.password", PASSWORD);
        options.put("assistant.documents", documents.toString());
        options.put("assistant.mail.inbox-host", "127.0.0.1");
        options.put("assistant.mail.inbox-port", mail.getImap().getPort());
        options.put("assistant.mail.inbox-tls", "false");
        options.put("assistant.mail.username", "owner");
        options.put("assistant.mail.password", PASSWORD);
        options.put("assistant.mail.from", OWNER);
        options.put("assistant.schedule.index-ms", "3600000");
        options.put("assistant.schedule.mail-ms", "3600000");
        options.put("assistant.schedule.reconcile-ms", "3600000");
        options.put("spring.mail.port", mail.getSmtp().getPort());
        String[] arguments = options.entrySet().stream().map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
        context = (ServletWebServerApplicationContext) new SpringApplicationBuilder(Assistant.class, Fixtures.class)
                .logStartupInfo(false).run(arguments);
    }

    void restart() {
        context.close();
        appStart();
    }

    @Override public void close() {
        if (context != null) {
            try {
                stopWorker();
                sql().execute("drop schema " + schema + " cascade");
            } finally {
                context.close();
            }
        }
        mail.stop();
    }

    String address() { return "http://127.0.0.1:" + context.getWebServer().getPort(); }
    JdbcTemplate sql() { return context.getBean(JdbcTemplate.class); }
    TestSender sender() { return context.getBean(TestSender.class); }
    FakeModel model() { return context.getBean(FakeModel.class); }
    void stopWorker() { context.getBean(com.github.kagkarlsson.scheduler.Scheduler.class).stop(); }

    Api signIn() throws Exception {
        Api client = new Api(address());
        client.login();
        return client;
    }

    void write(String name, String text) throws Exception { Files.writeString(documents.resolve(name), text); }

    static void pdf(Path path, String... pages) throws Exception {
        try (var pdf = new PDDocument()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (String text : pages) {
                var page = new PDPage();
                pdf.addPage(page);
                if (!text.isBlank()) {
                    try (var content = new PDPageContentStream(pdf, page)) {
                        content.beginText();
                        content.setFont(font, 14);
                        content.newLineAtOffset(70, 660);
                        content.showText(text);
                        content.endText();
                    }
                }
            }
            pdf.save(path.toFile());
        }
    }

    String incoming(String subject, String body, String parent) throws Exception {
        var settings = new Properties();
        settings.setProperty("mail.smtp.host", "127.0.0.1");
        settings.setProperty("mail.smtp.port", Integer.toString(mail.getSmtp().getPort()));
        var session = Session.getInstance(settings);
        var message = new MimeMessage(session);
        message.setFrom(CLASSMATE);
        message.addRecipient(Message.RecipientType.TO, new InternetAddress(OWNER));
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        message.setText(body, StandardCharsets.UTF_8.name());
        message.setSentDate(new Date());
        if (parent != null) {
            message.setHeader("In-Reply-To", parent);
            message.setHeader("References", parent);
        }
        message.saveChanges();
        String header = message.getMessageID();
        try (Transport smtp = session.getTransport("smtp")) {
            smtp.connect();
            smtp.sendMessage(message, message.getAllRecipients());
        }
        return header;
    }

    List<MimeMessage> outgoing() {
        return Arrays.stream(mail.getReceivedMessages()).filter(message -> {
            try {
                Address[] from = message.getFrom();
                return from.length > 0 && from[0].toString().contains(OWNER);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }).toList();
    }

    static String id(JsonNode node) { return node.path("id").asText(); }
    static String state(JsonNode node) { return node.path("status").asText(); }

    static final class Api {
        private final String base;
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .connectTimeout(Duration.ofSeconds(5)).build();
        private String csrf;

        Api(String base) { this.base = base; }

        void login() throws Exception {
            csrf = get("/session/csrf").path("token").asText();
            var response = raw("POST", "/session/login", "username=learner&password=" + PASSWORD,
                    "application/x-www-form-urlencoded", null, true);
            assertThat(response.statusCode()).isEqualTo(204);
            csrf = get("/session/csrf").path("token").asText();
        }

        JsonNode get(String path) throws Exception { return request("GET", path, null, null, 200); }

        JsonNode request(String method, String path, Object data, String key, int status) throws Exception {
            var response = raw(method, path, data == null ? null : JSON.writeValueAsString(data), "application/json", key, true);
            assertThat(response.statusCode()).as("%s %s → %s", method, path, response.body()).isEqualTo(status);
            return JSON.readTree(response.body());
        }

        HttpResponse<String> raw(String method, String path, String body, String media, String key, boolean token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base + "/api" + path)).timeout(Duration.ofSeconds(10))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            if (body != null) request.header("Content-Type", media);
            if (key != null) request.header("Idempotency-Key", key);
            if (token && csrf != null) request.header("X-CSRF-TOKEN", csrf);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        String submit(String path, Object data) throws Exception {
            return request("POST", path, data, UUID.randomUUID().toString(), 202).path("taskId").asText();
        }

        JsonNode done(String taskId) throws Exception {
            var task = waitFor("/tasks/" + taskId, node -> List.of("DONE", "FAILED").contains(state(node)));
            assertThat(state(task)).as(task.toString()).isEqualTo("DONE");
            return task;
        }

        JsonNode waitFor(String path, Predicate<JsonNode> reached) throws Exception {
            Instant deadline = Instant.now().plusSeconds(28);
            JsonNode latest;
            do {
                latest = get(path);
                if (reached.test(latest)) return latest;
                Thread.sleep(150);
            } while (Instant.now().isBefore(deadline));
            throw new AssertionError("Timed out at " + path + " with " + latest);
        }

        JsonNode answer(String question) throws Exception {
            return get("/answers/" + done(submit("/questions", Map.of("question", question))).path("resultId").asText());
        }

        JsonNode draft(String thread, String message) throws Exception {
            return get("/drafts/" + done(submit("/threads/" + thread + "/drafts", Map.of("messageId", message)))
                    .path("resultId").asText());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean @Primary FakeModel deterministicModel() { return new FakeModel(); }
        @Bean TestSender testSender(@org.springframework.beans.factory.annotation.Value("${spring.mail.port}") int port) {
            return new TestSender(port);
        }
    }

    static class TestSender extends JavaMailSenderImpl {
        final AtomicBoolean loseReceipt = new AtomicBoolean();

        TestSender(int port) {
            setHost("127.0.0.1");
            setPort(port);
        }

        @Override public void send(MimeMessage... messages) {
            super.send(messages);
            if (loseReceipt.getAndSet(false)) {
                throw new MailSendException("fixture: delivery accepted, receipt lost");
            }
        }
    }

    static class FakeModel implements Intelligence {
        final AtomicReference<Runnable> beforeNextEmbedding = new AtomicReference<>();

        @Override public List<float[]> embed(List<String> texts) {
            Runnable change = beforeNextEmbedding.getAndSet(null);
            if (change != null) change.run();
            return texts.stream().map(text -> {
                String lower = text.toLowerCase(Locale.ROOT);
                if (lower.matches("(?s).*(窗口|while|反复缩小|sliding).*")) return new float[]{1, 0, 0, 0, 0};
                if (lower.contains("needle pdf")) return new float[]{0, 1, 0, 0, 0};
                if (lower.contains("legacycounter")) return new float[]{0, 0, 1, 0, 0};
                if (lower.contains("freshcounter")) return new float[]{0, 0, 0, 1, 0};
                if (lower.contains("uncovered")) return new float[]{-1, -1, -1, -1, -1};
                return new float[]{0, 0, 0, 0, 1};
            }).toList();
        }

        @Override public String complete(String instruction, String input) {
            try {
                var message = JSON.readTree(input);
                var excerpts = message.path("excerpts");
                if (message.has("correspondence")) {
                    if (message.path("correspondence").toString().contains("Java") && !excerpts.isEmpty()) {
                        return JSON.writeValueAsString(Map.of("body", "Java 中用 while 反复收缩窗口，直到满足条件。", "sources", List.of(1), "gap", false));
                    }
                    return JSON.writeValueAsString(Map.of("body", "收到，谢谢你的问题。", "sources", List.of(), "gap", true));
                }
                return JSON.writeValueAsString(Map.of("claims", List.of(
                        Map.of("text", excerpts.get(0).asText(), "sources", List.of(1))), "gap", false));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }
}
