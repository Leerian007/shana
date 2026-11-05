package com.shana.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolSpecTest {

    @Test
    @DisplayName("无参工具自动带上空对象 schema")
    void shouldFillEmptySchema() {
        ToolSpec spec = ToolSpec.of("echo", "回显入参");

        assertThat(spec.parametersSchema()).isEqualTo("{\"type\":\"object\",\"properties\":{}}");
        assertThat(spec.name()).isEqualTo("echo");
    }

    @Test
    @DisplayName("schema 为空白时按无参处理，不留下非法 JSON")
    void shouldTreatBlankSchemaAsEmpty() {
        ToolSpec spec = new ToolSpec("echo", "回显入参", "   ");

        assertThat(spec.parametersSchema()).contains("\"properties\"");
    }

    @Test
    @DisplayName("工具名含中文或空格时构造失败：上游只接受字母数字下划线短横线")
    void shouldRejectIllegalName() {
        assertThatThrownBy(() -> ToolSpec.of("查群成员", "非法名字"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具名");
        assertThatThrownBy(() -> ToolSpec.of("has space", "非法名字"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolSpec.of("a".repeat(65), "太长"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("说明为空时构造失败：模型没有依据判断该不该调用")
    void shouldRejectBlankDescription() {
        assertThatThrownBy(() -> ToolSpec.of("echo", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具说明");
    }

    @Test
    @DisplayName("schema 不是合法 JSON 对象时构造失败，把错误从运行期提前到启动期")
    void shouldRejectInvalidSchema() {
        assertThatThrownBy(() -> new ToolSpec("echo", "回显", "{not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
        // 顶层是数组也不行：协议要求 parameters 是 object
        assertThatThrownBy(() -> new ToolSpec("echo", "回显", "[1,2,3]"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
