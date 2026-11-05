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
        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.specs()).isEmpty();
    }

    @Test
    @DisplayName("工具名重复时启动期直接失败")
    void shouldRejectDuplicateToolName() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(new EchoTool(), new EchoTool())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("echo");
    }

    @Test
    @DisplayName("说明书在构造期一次性构建，顺序与注册顺序一致")
    void shouldBuildSpecsInRegistrationOrder() {
        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool(), new UpperTool()));

        assertThat(registry.specs()).extracting("name").containsExactly("echo", "upper");
        assertThat(registry.specs().getFirst().description()).isEqualTo("回显入参，仅用于测试");
    }

    @Test
    @DisplayName("工具名不合法时构造注册表就失败，而不是等到第一次对话")
    void shouldRejectIllegalToolNameAtStartup() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(new ChineseNameTool())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具名");
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
        public String execute(ToolContext context, String arguments) {
            return arguments;
        }
    }

    private static final class UpperTool implements Tool {

        @Override
        public String name() {
            return "upper";
        }

        @Override
        public String description() {
            return "转大写，仅用于测试";
        }

        @Override
        public String execute(ToolContext context, String arguments) {
            return arguments.toUpperCase();
        }
    }

    private static final class ChineseNameTool implements Tool {

        @Override
        public String name() {
            return "查群成员";
        }

        @Override
        public String description() {
            return "名字不符合上游限制";
        }

        @Override
        public String execute(ToolContext context, String arguments) {
            return "";
        }
    }
}
