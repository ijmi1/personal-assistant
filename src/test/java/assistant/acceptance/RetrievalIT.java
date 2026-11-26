package assistant.acceptance;

import assistant.knowledge.Retrieval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_DB_URL", matches = ".+")
class RetrievalIT {
    @TempDir Path workspace;

    @Test void reusesTheQueryVectorAndReadsEvidenceFromTheNewIndex() throws Exception {
        try (var host = new Acceptance(workspace)) {
            host.write("orchid.md", "LegacyCounter reports an orchid error.");
            host.api.done(host.api.submit("/index/sync", null));
            var first = host.retrieval().search("orchid", 1);
            int calls = host.model().embeddingCalls.get();
            assertThat(host.retrieval().search("orchid", 1)).isEqualTo(first);
            assertThat(host.model().embeddingCalls.get()).isEqualTo(calls);

            host.write("orchid.md", "FreshCounter handles the orchid error now.");
            host.api.done(host.api.submit("/index/sync", null));
            int afterIndex = host.model().embeddingCalls.get();
            assertThat(host.retrieval().search("orchid", 1)).extracting(Retrieval.Evidence::excerpt)
                    .containsExactly("FreshCounter handles the orchid error now.");
            assertThat(host.model().embeddingCalls.get()).isEqualTo(afterIndex);
        }
    }

    @Test void ranksLexicalAndSemanticEvidenceAndReportsNoMatches() throws Exception {
        try (var host = new Acceptance(workspace)) {
            host.write("errors.md", "LegacyCounter reports an orchid error.");
            host.write("window.md", Acceptance.NOTE);
            host.api.done(host.api.submit("/index/sync", null));

            assertThat(host.retrieval().search("orchid", 5)).extracting(Retrieval.Evidence::excerpt)
                    .containsExactly("LegacyCounter reports an orchid error.");
            assertThat(host.retrieval().search("sliding", 5)).extracting(Retrieval.Evidence::excerpt)
                    .containsExactly(Acceptance.NOTE);

            host.write("mixed.md", "The orchid sliding example explains both terms.");
            host.api.done(host.api.submit("/index/sync", null));
            assertThat(host.retrieval().search("orchid sliding", 1)).extracting(Retrieval.Evidence::excerpt)
                    .containsExactly("The orchid sliding example explains both terms.");
            assertThat(host.retrieval().search("uncovered", 5)).isEmpty();
        }
    }
}
