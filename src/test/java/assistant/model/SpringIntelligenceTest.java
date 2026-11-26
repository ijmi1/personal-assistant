package assistant.model;

import assistant.common.Problem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringIntelligenceTest {
    @Test void recordsSuccessfulAndFailedModelCallsWithoutExposingProviderErrors() {
        var provider = new EmbeddingModel() {
            private int calls;

            @Override public EmbeddingResponse call(EmbeddingRequest request) {
                if (calls++ > 0) {
                    throw new IllegalStateException("provider details");
                }
                return new EmbeddingResponse(List.of(new Embedding(new float[]{1}, 0)));
            }

            @Override public float[] embed(Document document) {
                throw new UnsupportedOperationException();
            }
        };
        var input = List.of("example");
        var beans = new StaticListableBeanFactory();
        beans.addBean("embedding", provider);
        var registry = new SimpleMeterRegistry();
        var model = new SpringIntelligence(beans.getBeanProvider(ChatModel.class),
                beans.getBeanProvider(EmbeddingModel.class), true, registry);

        assertThat(model.embed(input).getFirst()).containsExactly(1);
        assertThatThrownBy(() -> model.embed(input)).isInstanceOf(Problem.class)
                .hasMessageContaining("嵌入模型不可用").hasMessageNotContaining("provider details");
        assertThat(registry.get("assistant.ai.calls").tags("operation", "embedding", "outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("assistant.ai.calls").tags("operation", "embedding", "outcome", "failure")
                .timer().count()).isEqualTo(1);
    }
}
