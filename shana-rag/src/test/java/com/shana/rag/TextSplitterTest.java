package com.shana.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 切分器测试。
 *
 * <p>重点不是"能不能切"，而是几个容易出错的边界：超长无标点文本、块间重叠、
 * 尾块过短、CRLF 混用。这些在真实文档里都会遇到，且出错时表现是"检索效果差"，
 * 很难从日志看出原因。
 */
class TextSplitterTest {

    @Test
    @DisplayName("空输入返回空列表，不产生空块")
    void shouldReturnEmptyForBlankInput() {
        TextSplitter splitter = new TextSplitter(100, 20, 10);

        assertThat(splitter.split(null)).isEmpty();
        assertThat(splitter.split("")).isEmpty();
        assertThat(splitter.split("   \n\n  \t ")).isEmpty();
    }

    @Test
    @DisplayName("短文本作为一个块返回，内容基本不变")
    void shouldKeepShortTextInOneChunk() {
        TextSplitter splitter = new TextSplitter(100, 20, 10);

        List<String> blocks = splitter.split("Flink 的 checkpoint 依赖 state backend。");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).isEqualTo("Flink 的 checkpoint 依赖 state backend。");
    }

    @Test
    @DisplayName("长文本切多块，除尾块合并外每块不超过上限")
    void shouldRespectMaxChars() {
        TextSplitter splitter = new TextSplitter(20, 0, 5);
        String text = "第一句话。第二句话。第三句话。第四句话。第五句话。";

        List<String> blocks = splitter.split(text);

        assertThat(blocks).hasSize(2);
        // 每块都是若干整句拼成，不会出现半个句子
        assertThat(blocks).allSatisfy(block -> assertThat(block).endsWith("。"));
        // 每句 5 字，上限 20：正好塞下四句，第五句另起一块
        assertThat(blocks.getFirst()).isEqualTo("第一句话。第二句话。第三句话。第四句话。");
        assertThat(blocks.get(1)).isEqualTo("第五句话。");
    }

    @Test
    @DisplayName("相邻块按 overlapChars 回退，横跨断点的内容不会丢")
    void shouldOverlapAdjacentChunks() {
        TextSplitter splitter = new TextSplitter(20, 8, 0);
        String text = "第一句话。第二句话。第三句话。第四句话。第五句话。第六句话。";

        List<String> blocks = splitter.split(text);

        assertThat(blocks).hasSize(2);
        // 第一块四句，第二块从第四句开始——断点处的"第四句话"两块都有
        assertThat(blocks.getFirst()).contains("第四句话。");
        assertThat(blocks.get(1)).startsWith("第四句话。");
    }

    @Test
    @DisplayName("没有标点的超长文本按长度硬切，不丢内容")
    void shouldHardSplitLongTextWithoutPunctuation() {
        TextSplitter splitter = new TextSplitter(20, 0, 5);
        String text = "a".repeat(50);

        List<String> blocks = splitter.split(text);

        assertThat(blocks).hasSize(3);
        assertThat(blocks).allSatisfy(block -> assertThat(block).hasSizeLessThanOrEqualTo(20));
        assertThat(String.join("", blocks)).isEqualTo(text);
    }

    @Test
    @DisplayName("CRLF 与多余空行会被归一化")
    void shouldNormalizeLineEndings() {
        TextSplitter splitter = new TextSplitter(200, 20, 10);

        List<String> blocks = splitter.split("第一行\r\n第二行\r\n\r\n\r\n第三行");

        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).isEqualTo("第一行\n第二行\n\n第三行");    }

    @Test
    @DisplayName("尾块过短时并入前一块，避免出现检索不到的碎块")
    void shouldMergeTinyTail() {
        TextSplitter splitter = new TextSplitter(20, 0, 10);
        // 四句塞满第一块，剩下 3 个字的短句不足 minChars，应当并回去而不是单独成块
        String text = "第一句话。第二句话。第三句话。第四句话。短句。";

        List<String> blocks = splitter.split(text);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst()).contains("短句。").startsWith("第一句话。");
    }

    @Test
    @DisplayName("参数非法时启动即失败，而不是运行期行为诡异")
    void shouldRejectInvalidOptions() {
        assertThatThrownBy(() -> new TextSplitter(0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxChars");
        // overlap 必须小于窗口，否则每块都完整包含上一块，切分原地打转
        assertThatThrownBy(() -> new TextSplitter(10, 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("overlapChars");
        assertThatThrownBy(() -> new TextSplitter(10, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
