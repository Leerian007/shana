package com.shana.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * pgvector 文本字面量格式测试。
 *
 * <p>格式错了数据库会报 "invalid input syntax for type vector"，但错误信息里
 * 没有上下文，只能靠这些用例提前挡住。
 */
class VectorLiteralTest {

    @Test
    @DisplayName("按 pgvector 的 [a,b,c] 形式输出")
    void shouldFormatAsPgVectorLiteral() {
        assertThat(VectorLiteral.format(new float[]{1f, -2.5f, 0f})).isEqualTo("[1.0,-2.5,0.0]");
    }

    @Test
    @DisplayName("单元素向量也合法")
    void shouldFormatSingleElement() {
        assertThat(VectorLiteral.format(new float[]{0.25f})).isEqualTo("[0.25]");
    }

    @Test
    @DisplayName("空向量直接拒绝，不拼出 '[]' 这种非法值")
    void shouldRejectEmptyVector() {
        assertThatThrownBy(() -> VectorLiteral.format(new float[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> VectorLiteral.format(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("NaN 与 Infinity 在应用层就挡住，并指出是哪一维")
    void shouldRejectNonFiniteValues() {
        assertThatThrownBy(() -> VectorLiteral.format(new float[]{0f, Float.NaN}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 1 维");
        assertThatThrownBy(() -> VectorLiteral.format(new float[]{Float.POSITIVE_INFINITY}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
