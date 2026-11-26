package assistant.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiConfigurationTest {
    @Test
    void configuredModelsUseTheExistingProviderUrl() throws IOException {
        Map<String, String> models = new ConcurrentHashMap<>();
        ObjectMapper json = new ObjectMapper();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                models.put(path, json.readTree(exchange.getRequestBody()).path("model").asText());
                String response = switch (path) {
                    case "/provider/v1/chat/completions" -> """
                            {"id":"chat-test","object":"chat.completion","created":1,"model":"test-chat",
                             "choices":[{"index":0,"finish_reason":"stop",
                                         "message":{"role":"assistant","content":"reply"}}]}
                            """;
                    case "/provider/v1/embeddings" -> """
                            {"object":"list","model":"test-embedding",
                             "data":[{"object":"embedding","index":0,"embedding":[0.25,0.5]}],
                             "usage":{"prompt_tokens":1,"total_tokens":1}}
                            """;
                    default -> "";
                };
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.isEmpty() ? 404 : 200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        provider.start();
        try {
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(OpenAiConfiguration.class)
                    .withConfiguration(AutoConfigurations.of(OpenAiChatAutoConfiguration.class,
                            OpenAiEmbeddingAutoConfiguration.class))
                    .withBean(ToolCallingManager.class, () -> ToolCallingManager.builder().build())
                    .withPropertyValues("ASSISTANT_MODEL_URL=http://127.0.0.1:" + provider.getAddress().getPort() + "/provider/",
                            "ASSISTANT_MODEL_KEY=test-key", "ASSISTANT_CHAT_MODEL=test-chat",
                            "ASSISTANT_EMBEDDING_MODEL=test-embedding")
                    .run(context -> {
                        assertThat(context.getBean(ChatModel.class).call("hello")).isEqualTo("reply");
                        assertThat(context.getBean(EmbeddingModel.class).embed("hello")).containsExactly(0.25f, 0.5f);
                    });
            assertThat(models).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "/provider/v1/chat/completions", "test-chat", "/provider/v1/embeddings", "test-embedding"));
        } finally {
            provider.stop(0);
        }
    }
}
