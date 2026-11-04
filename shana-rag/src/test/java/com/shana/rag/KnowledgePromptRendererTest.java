package com.shana.rag;

import com.shana.rag.store.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 参考资料渲染测试。
 *
 * <p>这块文本会原样进入 prompt，所以断言集中在"模型能不能看懂"：
 * 有没有出处、编号是否连续、空结果是否完全不拼。
 */
class KnowledgePromptRendererTest {

    @Test
    @DisplayName("没有命中片段时返回空串，调用方据此跳过注入")
    void shouldRenderNothingForEmptyInput() {
        assertThat(KnowledgePromptRenderer.render(List.of())).isEmpty();
        assertThat(KnowledgePromptRenderer.render(null)).isEmpty();
    }

    @Test
    @DisplayName("单条命中：包含说明、编号、出处与正文")
    void shouldRenderSingleChunk() {
        String text = KnowledgePromptRenderer.render(List.of(
                new RetrievedChunk(11L, 1L, "Flink 运维手册", "checkpoint 需要先配置 state backend", 0.82)));

        assertThat(text)
                .contains("【参考资料】")
                .contains("[1]")
                .contains("《Flink 运维手册》")
                .contains("checkpoint 需要先配置 state backend")
                .contains("不要编造");
    }

    @Test
    @DisplayName("多条命中：编号按顺序递增")
    void shouldNumberChunksInOrder() {
        String text = KnowledgePromptRenderer.render(List.of(
                new RetrievedChunk(1L, 1L, "A", "第一段", 0.9),
                new RetrievedChunk(2L, 1L, "A", "第二段", 0.8),
                new RetrievedChunk(3L, 2L, "B", "第三段", 0.7)));

        assertThat(text).contains("[1]", "[2]", "[3]");
        // 编号顺序与相似度顺序一致，模型据此知道哪段更相关
        assertThat(text.indexOf("第一段")).isLessThan(text.indexOf("第三段"));
    }

    @Test
    @DisplayName("标题为空时不渲染空的书名号")
    void shouldSkipBlankTitle() {
        String text = KnowledgePromptRenderer.render(List.of(
                new RetrievedChunk(1L, 1L, "  ", "正文内容", 0.5)));

        assertThat(text).doesNotContain("《》");
        assertThat(text).contains("正文内容");
    }

    @Test
    @DisplayName("超长标题截断，避免一条标题就吃掉大段上下文")
    void shouldTruncateLongTitle() {
        String longTitle = "标".repeat(200);

        String text = KnowledgePromptRenderer.render(List.of(
                new RetrievedChunk(1L, 1L, longTitle, "正文", 0.5)));

        assertThat(text).contains("…");
        assertThat(text).doesNotContain(longTitle);
    }
}
