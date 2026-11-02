package com.shana.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    @Test
    @DisplayName("按名称查找已注册的工具")
    void shouldFindRegisteredTool() {
        Tool echo = new EchoTool();
        ToolRegistry registry = new ToolRegistry(List.of(echo));

        assertThat(registry.find("echo")).containsSame(echo);
        assertThat(registry.all()).containsExactly(echo);
        assertThat(registry.names()).containsExactly("echo");
    }

    @Test
    @DisplayName("查找不存在的工具返回空 Optional")
    void shouldReturnEmptyWhenToolMissing() {
        ToolRegistry registry = new ToolRegistry(List.of());

        assertThat(registry.find("not-exists")).isEmpty();
        assertThat(registry.all()).isEmpty();
    }

    @Test
    @DisplayName("工具名重复时启动期直接失败")
    void shouldRejectDuplicateToolName() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(new EchoTool(), new EchoTool())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("echo");
    }

    /** 测试替身：原样返回入参。 */
    private static final class EchoTool implements Tool {

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "回显入参，仅用于测试";
        }

        @Override
        public String execute(String arguments) {
            return arguments;
        }
    }
}
