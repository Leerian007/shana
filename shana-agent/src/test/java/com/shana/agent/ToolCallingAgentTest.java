package com.shana.agent;

import com.shana.agent.tool.Tool;
import com.shana.agent.tool.ToolContext;
import com.shana.agent.tool.ToolRegistry;
import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.ChatRole;
import com.shana.model.LlmClient;
import com.shana.model.LlmClientRegistry;
import com.shana.model.LlmProvider;
import com.shana.model.TokenUsage;
import com.shana.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 工具编排测试：模型用脚本化替身，不发网络请求。
 *
 * <p>重点不是"能调通"，而是几条容易写错又不容易发现的规则：
 * tool 消息与 tool_call id 必须一一对应、工具失败不能中断对话、轮次超限要能收尾。
 */
class ToolCallingAgentTest {

    private static final ToolContext CONTEXT = new ToolContext("group-1", "u-1001");

    @Test
    @DisplayName("没有工具时只调一次模型，不出现工具轨迹")
    void shouldCallModelOnceWithoutTools() {
        ScriptedLlmClient client = new ScriptedLlmClient(answer("你好"));

        AgentResult result = newAgent(client, List.of(), 3).run(request());

        assertThat(result.content()).isEqualTo("你好");
        assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.toolsUsed()).isEmpty();
        assertThat(client.requests).hasSize(1);
        assertThat(client.requests.getFirst().hasTools()).isFalse();
    }

    @Test
    @DisplayName("单轮工具调用：执行后把结果回填，第二轮给出答案")
    void shouldExecuteSingleToolCallAndAnswer() {
        ScriptedLlmClient client = new ScriptedLlmClient(
                toolCall("call-1", "echo", "{\"text\":\"hi\"}"),
                answer("查到了"));

        AgentResult result = newAgent(client, List.of(new EchoTool()), 3).run(request());

        assertThat(result.content()).isEqualTo("查到了");
        assertThat(result.rounds()).isEqualTo(2);
        assertThat(result.toolsUsed()).containsExactly("echo");

        // 第二次请求里：原 prompt + 带 tool_calls 的 assistant + 一条 tool 结果
        List<ChatMessage> second = client.requests.get(1).messages();
        assertThat(second).hasSize(3);
        ChatMessage assistantTurn = second.get(1);
        assertThat(assistantTurn.role()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(assistantTurn.toolCalls()).hasSize(1);
        ChatMessage toolTurn = second.get(2);
        assertThat(toolTurn.role()).isEqualTo(ChatRole.TOOL);
        // id 必须原样回填，否则模型无法把结果和请求对上
        assertThat(toolTurn.toolCallId()).isEqualTo("call-1");
        assertThat(toolTurn.content()).isEqualTo("echo:{\"text\":\"hi\"}");
    }

    @Test
    @DisplayName("同一轮多个工具调用：每个都要有对应的 tool 结果，顺序与 id 不能乱")
    void shouldAnswerEveryToolCallInSameRound() {
        ScriptedLlmClient client = new ScriptedLlmClient(
                new ChatResponse(null, LlmProvider.OPENAI_COMPATIBLE, "m", TokenUsage.unknown(),
                        List.of(new ToolCall("a", "echo", "{}"), new ToolCall("b", "echo", "{}"))),
                answer("ok"));

        AgentResult result = newAgent(client, List.of(new EchoTool()), 3).run(request());

        assertThat(result.toolsUsed()).containsExactly("echo", "echo");
        List<ChatMessage> second = client.requests.get(1).messages();
        assertThat(second).hasSize(4);
        assertThat(second.get(2).toolCallId()).isEqualTo("a");
        assertThat(second.get(3).toolCallId()).isEqualTo("b");
    }

    @Test
    @DisplayName("模型要求调用不存在的工具：回填错误与可用清单，对话继续")
    void shouldFeedBackUnknownToolError() {
        ScriptedLlmClient client = new ScriptedLlmClient(
                toolCall("call-1", "drop_database", "{}"),
                answer("那个工具不存在，我直接回答"));

        AgentResult result = newAgent(client, List.of(new EchoTool()), 3).run(request());

        assertThat(result.content()).isEqualTo("那个工具不存在，我直接回答");
        assertThat(client.requests.get(1).messages().get(2).content())
                .contains("没有名为 drop_database 的工具")
                .contains("echo");
    }

    @Test
    @DisplayName("工具抛异常：转成错误文本回填，不向上抛")
    void shouldConvertToolFailureToText() {
        ScriptedLlmClient client = new ScriptedLlmClient(
                toolCall("call-1", "boom", "{}"),
                answer("查询失败了"));

        AgentResult result = newAgent(client, List.of(new FailingTool()), 3).run(request());

        assertThat(result.content()).isEqualTo("查询失败了");
        assertThat(client.requests.get(1).messages().get(2).content())
                .startsWith("错误：工具 boom 执行失败")
                .contains("数据库连不上");
    }

    @Test
    @DisplayName("工具返回空内容：给出占位说明，避免模型以为拿到了空数据")
    void shouldDescribeEmptyToolResult() {
        ScriptedLlmClient client = new ScriptedLlmClient(
                toolCall("call-1", "silent", "{}"),
                answer("没查到"));

        newAgent(client, List.of(new SilentTool()), 3).run(request());

        assertThat(client.requests.get(1).messages().get(2).content()).contains("没有任何内容");
    }

    @Test
    @DisplayName("轮次超限：撤掉工具再问一次，逼模型用已有结果作答")
    void shouldForceAnswerWhenRoundsExhausted() {
        ScriptedLlmClient client = new ScriptedLlmClient(
                toolCall("c1", "echo", "{}"),
                toolCall("c2", "echo", "{}"),
                answer("先这样回答"));

        AgentResult result = newAgent(client, List.of(new EchoTool()), 2).run(request());

        assertThat(result.content()).isEqualTo("先这样回答");
        assertThat(result.rounds()).isEqualTo(3);
        // 第 2 轮之后已经到上限，第 3 次请求必须不带工具，否则模型会继续调下去
        assertThat(client.requests.get(2).hasTools()).isFalse();
        assertThat(client.requests.get(1).hasTools()).isTrue();
    }

    @Test
    @DisplayName("关闭工具开关后，即使注册了工具也不传给模型")
    void shouldNotExposeToolsWhenDisabled() {
        ScriptedLlmClient client = new ScriptedLlmClient(answer("纯对话"));

        ToolRegistry registry = new ToolRegistry(List.of(new EchoTool()));
        new ToolCallingAgent(registryOf(client), registry, false, 3).run(request());

        assertThat(client.requests.getFirst().hasTools()).isFalse();
    }

    @Test
    @DisplayName("多轮调用的 token 用量要累加，不能只报最后一轮")
    void shouldAccumulateUsageAcrossRounds() {
        ScriptedLlmClient client = new ScriptedLlmClient(
                new ChatResponse(null, LlmProvider.OPENAI_COMPATIBLE, "m", new TokenUsage(10, 3),
                        List.of(new ToolCall("c1", "echo", "{}"))),
                new ChatResponse("好了", LlmProvider.OPENAI_COMPATIBLE, "m", new TokenUsage(20, 7)));

        AgentResult result = newAgent(client, List.of(new EchoTool()), 3).run(request());

        assertThat(result.usage().promptTokens()).isEqualTo(30);
        assertThat(result.usage().completionTokens()).isEqualTo(10);
    }

    @Test
    @DisplayName("工具结果过长会被截断，避免挤掉对话历史")
    void shouldTruncateHugeToolResult() {
        ScriptedLlmClient client = new ScriptedLlmClient(
                toolCall("c1", "huge", "{}"),
                answer("好"));

        newAgent(client, List.of(new HugeTool()), 3).run(request());

        String fedBack = client.requests.get(1).messages().get(2).content();
        assertThat(fedBack).hasSizeLessThan(4100).contains("已截断");
    }

    @Test
    @DisplayName("轮次超限后模型仍要求调工具：判定为上游异常并抛错")
    void shouldFailWhenModelStillRequestsToolsAfterDisabling() {
        ScriptedLlmClient client = new ScriptedLlmClient(
                toolCall("c1", "echo", "{}"),
                toolCall("c2", "echo", "{}"));

        Agent agent = newAgent(client, List.of(new EchoTool()), 1);
        BusinessException error = catchThrowableOfType(() -> agent.run(request()), BusinessException.class);

        assertThat(error.getMessage()).contains("仍要求调用工具");
        assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    private static ToolCallingAgent newAgent(LlmClient client, List<Tool> tools, int maxRounds) {
        return new ToolCallingAgent(registryOf(client), new ToolRegistry(tools), true, maxRounds);
    }

    private static LlmClientRegistry registryOf(LlmClient client) {
        return new LlmClientRegistry(List.of(client));
    }

    private static AgentRequest request() {
        return new AgentRequest(LlmProvider.OPENAI_COMPATIBLE,
                List.of(ChatMessage.user("群里谁最活跃")), CONTEXT);
    }

    private static ChatResponse answer(String content) {
        return new ChatResponse(content, LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown());
    }

    private static ChatResponse toolCall(String id, String name, String arguments) {
        return new ChatResponse(null, LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown(),
                List.of(new ToolCall(id, name, arguments)));
    }

    /** 按脚本依次返回响应的模型替身，同时记录每次请求，便于断言 prompt 结构。 */
    private static final class ScriptedLlmClient implements LlmClient {

        private final Deque<ChatResponse> responses = new ArrayDeque<>();
        private final List<ChatRequest> requests = new ArrayList<>();

        ScriptedLlmClient(ChatResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public LlmProvider provider() {
            return LlmProvider.OPENAI_COMPATIBLE;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            ChatResponse next = responses.poll();
            if (next == null) {
                throw new IllegalStateException("脚本里的响应不够用了");
            }
            return next;
        }
    }

    /** 原样回显入参的工具，用来验证参数透传与结果回填。 */
    private record EchoTool() implements Tool {

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "回显入参";
        }

        @Override
        public String execute(ToolContext context, String arguments) {
            return "echo:" + arguments;
        }
    }

    private record FailingTool() implements Tool {

        @Override
        public String name() {
            return "boom";
        }

        @Override
        public String description() {
            return "总是失败的工具";
        }

        @Override
        public String execute(ToolContext context, String arguments) {
            throw new IllegalStateException("数据库连不上");
        }
    }

    private record SilentTool() implements Tool {

        @Override
        public String name() {
            return "silent";
        }

        @Override
        public String description() {
            return "不返回内容的工具";
        }

        @Override
        public String execute(ToolContext context, String arguments) {
            return "  ";
        }
    }

    private record HugeTool() implements Tool {

        @Override
        public String name() {
            return "huge";
        }

        @Override
        public String description() {
            return "返回超长结果的工具";
        }

        @Override
        public String execute(ToolContext context, String arguments) {
            return "x".repeat(10_000);
        }
    }
}
