package com.shana.core.memory;

import com.shana.memory.FactKind;
import com.shana.memory.MemoryFact;
import com.shana.memory.MemorySnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆渲染测试：决定"模型到底看到什么"。
 */
class MemoryPromptRendererTest {

    @Test
    @DisplayName("没有任何记忆时返回空串，由调用方跳过拼接")
    void shouldRenderNothingWhenEmpty() {
        assertThat(MemoryPromptRenderer.render(null)).isEmpty();
        assertThat(MemoryPromptRenderer.render(MemorySnapshot.empty())).isEmpty();
    }

    @Test
    @DisplayName("只有摘要")
    void shouldRenderSummaryOnly() {
        String text = MemoryPromptRenderer.render(new MemorySnapshot("群里在讨论发布节奏", 3L, List.of()));

        assertThat(text).contains("群聊长期记忆").contains("摘要：群里在讨论发布节奏").doesNotContain("已知信息");
    }

    @Test
    @DisplayName("只有事实时也要给标题")
    void shouldRenderFactsWithoutSummary() {
        String text = MemoryPromptRenderer.render(new MemorySnapshot(null, 0L,
                List.of(MemoryFact.of(FactKind.PERSON, "张三", "在学 Flink"))));

        assertThat(text).contains("已知信息").contains("- 张三：在学 Flink").doesNotContain("摘要：");
    }

    @Test
    @DisplayName("无主体的事实不加冒号，避免出现「- ：xxx」这种残句")
    void shouldRenderFactWithoutSubject() {
        String text = MemoryPromptRenderer.render(new MemorySnapshot(null, 0L,
                List.of(MemoryFact.of(FactKind.DECISION, "", "周五晚发版"))));

        assertThat(text).contains("- 周五晚发版").doesNotContain("：周五晚发版");
    }

    @Test
    @DisplayName("不渲染类别元数据，省 token")
    void shouldNotRenderKind() {
        String text = MemoryPromptRenderer.render(new MemorySnapshot(null, 0L,
                List.of(MemoryFact.of(FactKind.PERSON, "张三", "在学 Flink"))));

        assertThat(text).doesNotContain("PERSON");
    }
}
