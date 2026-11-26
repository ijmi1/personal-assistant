package assistant.model;

import assistant.common.Problem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Duration;

@Component
class SpringIntelligence implements Intelligence {
    private static final Logger LOG = LoggerFactory.getLogger(SpringIntelligence.class);
    private final ObjectProvider<ChatModel> chat;
    private final ObjectProvider<EmbeddingModel> embedding;
    private final boolean enabled;

    SpringIntelligence(ObjectProvider<ChatModel> chat, ObjectProvider<EmbeddingModel> embedding,
                       @Value("${assistant.model-enabled}") boolean enabled) {
        this.chat = chat;
        this.embedding = embedding;
        this.enabled = enabled;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (!enabled || embedding.getIfAvailable() == null) {
            throw Problem.unavailable("嵌入模型未配置");
        }
        long started = System.nanoTime();
        try {
            return embedding.getObject().embed(texts);
        } catch (RuntimeException e) {
            throw Problem.unavailable("嵌入模型不可用，请检查模型配置或稍后重试");
        } finally {
            LOG.info("嵌入请求耗时 {} ms，输入 {} 段", elapsed(started), texts.size());
        }
    }

    @Override
    public String complete(String instruction, String input) {
        if (!enabled || chat.getIfAvailable() == null) {
            throw Problem.unavailable("对话模型未配置");
        }
        long started = System.nanoTime();
        try {
            return chat.getObject().call(new Prompt(List.of(new SystemMessage(instruction), new UserMessage(input))))
                    .getResult().getOutput().getText();
        } catch (RuntimeException e) {
            throw Problem.unavailable("对话模型不可用，请检查模型配置或稍后重试");
        } finally {
            LOG.info("对话请求耗时 {} ms", elapsed(started));
        }
    }

    private static long elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
