package assistant.acceptance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ASSISTANT_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ASSISTANT_BROWSER_E2E", matches = "1")
class StudyReplyE2E {
    @TempDir Path workspace;

    @Test void replyOnPhone() throws Exception {
        try (var host = new Acceptance(workspace)) {
            host.write("滑动窗口.md", "# Java\n\n" + Acceptance.NOTE);
            Acceptance.pdf(host.documents.resolve("练习册.pdf"), "one", "two", "needle PDF", "four",
                    "five", "six", "seven", "eight", "nine");
            host.api.done(host.api.submit("/index/sync", null));
            String parent = host.incoming("滑动窗口", "我们约定用 Java。", null);
            host.incoming("Re: 滑动窗口", "滑动窗口为什么要用 while？", parent);
            host.api.done(host.api.submit("/mail/sync", null));
            var command = new ProcessBuilder("node", "node_modules/@playwright/test/cli.js", "test", "--workers=1")
                    .directory(Path.of("frontend").toFile())
                    .redirectErrorStream(true).redirectOutput(workspace.resolve("playwright.log").toFile());
            command.environment().put("ASSISTANT_E2E_BASE_URL", host.address());
            command.environment().put("ASSISTANT_E2E_PASSWORD", Acceptance.PASSWORD);
            var browser = command.start();
            try {
                assertThat(browser.waitFor(110, TimeUnit.SECONDS)).as("Playwright timeout").isTrue();
                assertThat(browser.exitValue()).as(java.nio.file.Files.readString(workspace.resolve("playwright.log"))).isZero();
            } finally {
                if (browser.isAlive()) browser.destroyForcibly();
            }
            assertThat(host.outgoing()).hasSize(1);
            var delivered = host.outgoing().getFirst();
            assertThat(delivered.getContent().toString().strip()).isEqualTo("我的 Java 示例：窗口超限时反复移动左边界。");
            assertThat(delivered.getSubject()).isEqualTo("Re: 滑动窗口");
        }
    }
}
