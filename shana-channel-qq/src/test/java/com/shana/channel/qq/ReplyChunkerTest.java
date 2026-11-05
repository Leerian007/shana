package com.shana.channel.qq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回复分段测试。
 *
 * <p>重点不是"能切开"，而是切得<b>不破坏内容</b>：句中断开、emoji 劈成半个字符，
 * 这两种问题在 QQ 里表现成乱码或语义断裂，用户只会觉得机器人说话很怪。
 */
class ReplyChunkerTest {

    @Test
    @DisplayName("短文本不切分")
    void shouldKeepShortTextIntact() {
        ReplyChunker chunker = new ReplyChunker(100);

        assertThat(chunker.split("好的，已经查过了。")).containsExactly("好的，已经查过了。");
    }

    @Test
    @DisplayName("空内容返回空列表，调用方据此跳过发送")
    void shouldReturnEmptyForBlank() {
        ReplyChunker chunker = new ReplyChunker(100);

        assertThat(chunker.split("")).isEmpty();
        assertThat(chunker.split("   \n  ")).isEmpty();
        assertThat(chunker.split(null)).isEmpty();
    }

    @Test
    @DisplayName("超长文本按上限切分，每段都不超限")
    void shouldSplitLongTextWithinLimit() {
        ReplyChunker chunker = new ReplyChunker(10);
        String text = "一二三四五六七八九十十一十二";

        List<String> chunks = chunker.split(text);

        assertThat(chunks).containsExactly("一二三四五六七八九十", "十一十二");
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.codePointCount(0, chunk.length()))
                .isLessThanOrEqualTo(10));
    }

    @Test
    @DisplayName("优先在换行处断开，段落不会被拆散")
    void shouldPreferNewlineBoundary() {
        ReplyChunker chunker = new ReplyChunker(12);

        List<String> chunks = chunker.split("第一行内容\n第二行内容很长很长");

        assertThat(chunks).containsExactly("第一行内容", "第二行内容很长很长");
    }

    @Test
    @DisplayName("优先在句号处断开，不会切在句子中间")
    void shouldPreferSentenceBoundary() {
        ReplyChunker chunker = new ReplyChunker(10);

        List<String> chunks = chunker.split("这是第一句话。这是第二句话。这是第三句话。");

        assertThat(chunks).containsExactly("这是第一句话。", "这是第二句话。", "这是第三句话。");
    }

    @Test
    @DisplayName("emoji 不会被代理对劈开")
    void shouldNotSplitSurrogatePairs() {
        ReplyChunker chunker = new ReplyChunker(100);
        String text = "😀".repeat(250);

        List<String> chunks = chunker.split(text);

        assertThat(chunks).hasSize(3);
        for (String chunk : chunks) {
            // 正常的 emoji 是两个 char；长度为奇数说明有一个代理被孤立了
            assertThat(chunk.length() % 2).isZero();
            assertThat(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))).isFalse();
        }
    }

    @Test
    @DisplayName("切完的段落总长度与原文本一致（忽略被 strip 掉的空白）")
    void shouldNotLoseContent() {
        ReplyChunker chunker = new ReplyChunker(8);
        String text = "第一段内容。第二段内容。第三段内容。";

        List<String> chunks = chunker.split(text);

        assertThat(String.join("", chunks)).isEqualTo(text);
    }

    @Test
    @DisplayName("上限必须为正数")
    void shouldRejectNonPositiveLimit() {
        assertThatThrownBy(() -> new ReplyChunker(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
