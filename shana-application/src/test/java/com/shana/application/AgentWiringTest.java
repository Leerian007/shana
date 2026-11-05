package com.shana.application;

import com.shana.agent.Agent;
import com.shana.agent.ToolCallingAgent;
import com.shana.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具装配的集成测试。
 *
 * <p>为什么要专门测"装配"：{@link ToolRegistry} 靠 {@code List<Tool>} 注入收集工具，
 * 而工具实现分散在 infrastructure、扫描路径来自启动类。任何一环配错，
 * 结果都是"注册表是空的"——系统照常启动、照常回答，只是模型永远查不到数据，
 * 而且没有任何报错。这种沉默失败只能靠启动完整容器来发现。
 */
@ActiveProfiles("test")
@SpringBootTest
class AgentWiringTest {

    @Autowired
    private Agent agent;

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    @DisplayName("Agent 由 ToolCallingAgent 实现，问答链路拿到的不是空壳")
    void shouldWireToolCallingAgent() {
        assertThat(agent).isInstanceOf(ToolCallingAgent.class);
    }

    @Test
    @DisplayName("群聊统计工具被自动注册，说明书内容可用于模型判断")
    void shouldRegisterGroupMessageStatsTool() {
        assertThat(toolRegistry.names()).contains("group_message_stats");
        assertThat(toolRegistry.find("group_message_stats")).isPresent();
        assertThat(toolRegistry.specs())
                .anySatisfy(spec -> {
                    assertThat(spec.name()).isEqualTo("group_message_stats");
                    // 说明里必须写清"什么时候用"，否则模型不会主动调用
                    assertThat(spec.description()).contains("发言");
                    assertThat(spec.parametersSchema()).contains("days");
                });
    }
}
