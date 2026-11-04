package com.shana.core.memory;

import com.shana.memory.FactKind;
import com.shana.memory.MemoryFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 摘要结果解析测试。
 *
 * <p>这一层的价值在于"模型不听话时会发生什么"：真机上它一定会裹代码块、
 * 加解释、把枚举写成小写。解析器守不住这些，线上表现就是"摘要功能时好时坏"。
 */
class SummaryDraftParserTest {

    private final SummaryDraftParser parser = new SummaryDraftParser();

    @Test
    @DisplayName("标准 JSON 直接解析")
    void shouldParsePlainJson() {
        SummaryDraft draft = parser.parse("""
                {"summary":"群里在讨论 Flink 的状态后端","facts":[{"kind":"PERSON","subject":"张三","content":"在学 Flink"}]}
                """);

        assertThat(draft.summary()).isEqualTo("群里在讨论 Flink 的状态后端");
        assertThat(draft.facts()).containsExactly(MemoryFact.of(FactKind.PERSON, "张三", "在学 Flink"));
    }

    @Test
    @DisplayName("包在 ```json 代码块里也能解析")
    void shouldParseFencedJson() {
        SummaryDraft draft = parser.parse("""
                ```json
                {"summary":"讨论了发布节奏","facts":[]}
                ```
                """);

        assertThat(draft.summary()).isEqualTo("讨论了发布节奏");
        assertThat(draft.facts()).isEmpty();
    }

    @Test
    @DisplayName("前后带解释文字也能解析")
    void shouldParseJsonSurroundedByProse() {
        SummaryDraft draft = parser.parse("好的，结果如下：\n{\"summary\":\"定了周五上线\",\"facts\":[]}\n希望有帮助。");

        assertThat(draft.summary()).isEqualTo("定了周五上线");
    }

    @Test
    @DisplayName("摘要为空时判失败，避免把已积累的记忆抹掉")
    void shouldRejectEmptySummary() {
        assertThatThrownBy(() -> parser.parse("{\"summary\":\"   \",\"facts\":[]}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("摘要为空");
    }

    @Test
    @DisplayName("不是 JSON 时抛出可读异常")
    void shouldRejectNonJson() {
        assertThatThrownBy(() -> parser.parse("我觉得这个群挺活跃的"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("找不到 JSON");
    }

    @Test
    @DisplayName("未知类别降级为 FACT，不丢信息")
    void shouldDowngradeUnknownKind() {
        SummaryDraft draft = parser.parse("""
                {"summary":"有结论了","facts":[{"kind":"opinion","subject":"李四","content":"偏好 Java"}]}
                """);

        assertThat(draft.facts()).extracting(MemoryFact::kind).containsExactly(FactKind.FACT);
    }

    @Test
    @DisplayName("类别大小写不敏感")
    void shouldAcceptLowercaseKind() {
        SummaryDraft draft = parser.parse("""
                {"summary":"有结论了","facts":[{"kind":"decision","subject":"","content":"周五上线"}]}
                """);

        assertThat(draft.facts()).extracting(MemoryFact::kind).containsExactly(FactKind.DECISION);
    }

    @Test
    @DisplayName("事实条数超过上限时截断")
    void shouldCapFactCount() {
        StringBuilder facts = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0) {
                facts.append(',');
            }
            facts.append("{\"kind\":\"FACT\",\"subject\":\"\",\"content\":\"事实").append(i).append("\"}");
        }

        SummaryDraft draft = parser.parse("{\"summary\":\"很多事\",\"facts\":[" + facts + "]}");

        assertThat(draft.facts()).hasSize(8);
    }

    @Test
    @DisplayName("内容为空的事实被丢弃，长度超限的按列宽截断")
    void shouldDropEmptyAndTruncateLongContent() {
        String longContent = "很".repeat(300);
        SummaryDraft draft = parser.parse("""
                {"summary":"有结论了","facts":[
                  {"kind":"FACT","subject":"","content":"   "},
                  {"kind":"FACT","subject":"张三","content":"%s"}
                ]}
                """.formatted(longContent));

        assertThat(draft.facts()).hasSize(1);
        // 200 列宽：199 个字符 + 省略号
        assertThat(draft.facts().get(0).content()).hasSize(200).endsWith("…");
    }
}
