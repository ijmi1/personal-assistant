package assistant.model;

import assistant.common.Problem;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

@Component
class SpringIntelligence implements Intelligence {
    private final ObjectProvider<ChatModel> chat;
    private final ObjectProvider<EmbeddingModel> embedding;
    private final boolean enabled;
    private final MeterRegistry metrics;

    SpringIntelligence(ObjectProvider<ChatModel> chat, ObjectProvider<EmbeddingModel> embedding,
                       @Value("${assistant.model-enabled}") boolean enabled, MeterRegistry metrics) {
        this.chat = chat;
        this.embedding = embedding;
        this.enabled = enabled;
        this.metrics = metrics;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (!enabled || embedding.getIfAvailable() == null) {
            throw Problem.unavailable("嵌入模型未配置");
        }
        return timed("embedding", "嵌入模型不可用，请检查模型配置或稍后重试",
                () -> embedding.getObject().embed(texts));
    }

    @Override
    public String complete(String instruction, String input) {
        if (!enabled || chat.getIfAvailable() == null) {
            throw Problem.unavailable("对话模型未配置");
        }
        return timed("chat", "对话模型不可用，请检查模型配置或稍后重试",
                () -> chat.getObject().call(new Prompt(List.of(new SystemMessage(instruction), new UserMessage(input))))
                        .getResult().getOutput().getText());
    }

    private <T> T timed(String operation, String reason, Supplier<T> call) {
        long start = System.nanoTime();
        String outcome = "success";
        try {
            return call.get();
        } catch (RuntimeException failure) {
            outcome = "failure";
            throw Problem.unavailable(reason);
        } finally {
            metrics.timer("assistant.ai.calls", "operation", operation, "outcome", outcome)
                    .record(Duration.ofNanos(System.nanoTime() - start));
        }
    }
}
