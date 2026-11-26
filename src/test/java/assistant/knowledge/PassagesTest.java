package assistant.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PassagesTest {
    @Test void indexesChineseBigramsAndNormalizesIdentifiers() {
        assertThat(Passages.terms("滑动窗口 Sliding_Window sliding_window"))
                .isEqualTo("sliding_window 动窗 滑动 窗口");
    }

    @Test void choosesTheMatchingContextWhenTextRepeats() {
        var before = unit(1, "引言");
        var excerpt = unit(2, "同一段原文");
        var ending = unit(3, "结束");
        var saved = Passages.location(List.of(before, excerpt, ending), 1);
        var moved = List.of(unit(1, "背景"), unit(2, "同一段原文"), unit(3, "另一个结尾"),
                unit(4, "引言"), unit(5, "同一段原文"), unit(6, "结束"));

        assertThat(Passages.locate(moved, saved.excerpt(), saved.context()).anchor()).isEqualTo(moved.get(4).anchor());
        assertThat(Passages.locate(moved, saved.excerpt(), "其他上下文").anchor()).isNull();
    }

    private static SourceFiles.Unit unit(int paragraph, String text) {
        return new SourceFiles.Unit(new SourceFiles.Anchor(null, List.of(), paragraph), text, text);
    }
}
