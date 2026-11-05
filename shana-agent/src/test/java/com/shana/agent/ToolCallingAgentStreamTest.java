package com.shana.agent;

import com.shana.agent.tool.Tool;
import com.shana.agent.tool.ToolContext;
import com.shana.agent.tool.ToolRegistry;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.LlmClient;
import com.shana.model.LlmClientRegistry;
import com.shana.model.LlmProvider;
import com.shana.model.StreamingLlmClient;
import com.shana.model.TokenUsage;
import com.shana.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流式 Agent 测试：模型用脚本化替身，验证"增量怎么转发、工具轮次怎么衔接"。
 *
 * <p>最要紧的一条是<b>过渡语不能混进最终答复</b>：模型可能先输出"我查一下"再调工具，
 * 这句话已经推给客户端了，但落库的正文必须只是第二轮的答案，否则记忆里会混进一堆废话。
 */
class ToolCallingAgentStreamTest {

    private static final LlmProvider PROVIDER = LlmProvider.OPENAI_COMPATIBLE;
    private static final ToolContext CONTEXT = new ToolContext("group-1", "u-1001");

    @Test
    @DisplayName("纯文本流：每段增量都转发，最终正文是拼接结果")
    void shouldForwardTextDeltas() {
        ScriptedStreamingClient client = new ScriptedStreamingClient(
                round(answer("你好呀"), "你", "好", "呀"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = newAgent(client, List.of(), 3).run(request(), listener);

        assertThat(listener.deltas).containsExactly("你", "好", "呀");
        assertThat(result.content()).isEqualTo("你好呀");
        assertThat(listener.rounds).containsExactly(1);
        assertThat(listener.tools).isEmpty();
        assertThat(result.rounds()).isEqualTo(1);
    }

    @Test
    @DisplayName("工具轮次：先通知工具名，第二轮的文字才推给用户")
    void shouldStreamOnlyAfterToolRound() {
        ScriptedStreamingClient client = new ScriptedStreamingClient(
                round(toolCall("call-1", "echo", "{}")),
                round(answer("查到了"), "查到", "了"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = newAgent(client, List.of(new EchoTool()), 3).run(request(), listener);

        assertThat(listener.rounds).containsExactly(1, 2);
        assertThat(listener.tools).containsExactly("echo");
        assertThat(listener.deltas).containsExactly("查到", "了");
        assertThat(result.content()).isEqualTo("查到了");
        assertThat(result.toolsUsed()).containsExactly("echo");
    }

    @Test
    @DisplayName("模型边说话边调工具：过渡语会推给客户端，但不进入最终正文")
    void shouldNotKeepTransitionTextInAnswer() {
        ScriptedStreamingClient client = new ScriptedStreamingClient(
                round(talkingToolCall("我查一下", "call-1", "echo", "{}"), "我查一下"),
                round(answer("最近七天发言最多的是张三"), "最近七天发言最多的是张三"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = newAgent(client, List.of(new EchoTool()), 3).run(request(), listener);

        // 客户端确实看到了过渡语（所以需要第二轮开始时发 reset 清屏）
        assertThat(listener.deltas).containsExactly("我查一下", "最近七天发言最多的是张三");
        assertThat(listener.rounds).containsExactly(1, 2);
        // 但落库的正文只有真正的答案
        assertThat(result.content()).isEqualTo("最近七天发言最多的是张三");
    }

    @Test
    @DisplayName("模型不支持流式时降级：整段正文作为一次增量推出")
    void shouldFallBackToWholeAnswer() {
        ScriptedNonStreamingClient client = new ScriptedNonStreamingClient(answer("一次性给的"));
        RecordingListener listener = new RecordingListener();

        AgentResult result = newAgent(client, List.of(), 3).run(request(), listener);

        assertThat(listener.deltas).containsExactly("一次性给的");
        assertThat(result.content()).isEqualTo("一次性给的");
    }

    @Test
    @DisplayName("流式请求与普通请求使用同一份工具清单")
    void shouldSendToolsInStreamingRequest() {
        ScriptedStreamingClient client = new ScriptedStreamingClient(
                round(answer("ok"), "ok"));

        newAgent(client, List.of(new EchoTool()), 3).run(request(), new RecordingListener());

        assertThat(client.requests.getFirst().tools()).hasSize(1);
        assertThat(client.requests.getFirst().tools().getFirst().name()).isEqualTo("echo");
    }

    // ---------- 替身与辅助 ----------

    private static ToolCallingAgent newAgent(LlmClient client, List<Tool> tools, int maxRounds) {
        return new ToolCallingAgent(new LlmClientRegistry(List.of(client)), new ToolRegistry(tools), true, maxRounds);
    }

    private static AgentRequest request() {
        return new AgentRequest(PROVIDER, List.of(ChatMessage.user("群里谁最活跃")), CONTEXT);
    }

    private static ChatResponse answer(String content) {
        return new ChatResponse(content, PROVIDER, "test-model", TokenUsage.unknown());
    }

    private static ChatResponse toolCall(String id, String name, String arguments) {
        return new ChatResponse(null, PROVIDER, "test-model", TokenUsage.unknown(),
                List.of(new ToolCall(id, name, arguments)));
    }

    private static ChatResponse talkingToolCall(String content, String id, String name, String arguments) {
        return new ChatResponse(content, PROVIDER, "test-model", TokenUsage.unknown(),
                List.of(new ToolCall(id, name, arguments)));
    }

    private static Round round(ChatResponse response, String... chunks) {
        return new Round(response, List.of(chunks));
    }

    /** 一轮模型调用：先吐出若干增量，再返回聚合后的响应。 */
    private record Round(ChatResponse response, List<String> chunks) {
    }

    private static final class ScriptedStreamingClient implements StreamingLlmClient {

        private final Deque<Round> rounds = new ArrayDeque<>();
        private final List<ChatRequest> requests = new ArrayList<>();

        ScriptedStreamingClient(Round... rounds) {
            this.rounds.addAll(List.of(rounds));
        }

        @Override
        public LlmProvider provider() {
            return PROVIDER;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new UnsupportedOperationException("流式客户端不应走非流式入口");
        }

        @Override
        public ChatResponse streamChat(ChatRequest request, Consumer<String> onText) {
            requests.add(request);
            Round round = rounds.poll();
            round.chunks().forEach(onText);
            return round.response();
        }
    }

    private static final class ScriptedNonStreamingClient implements LlmClient {

        private final Deque<ChatResponse> responses = new ArrayDeque<>();

        ScriptedNonStreamingClient(ChatResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public LlmProvider provider() {
            return PROVIDER;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return responses.poll();
        }
    }

    private static final class RecordingListener implements AgentListener {

        private final List<Integer> rounds = new ArrayList<>();
        private final List<String> deltas = new ArrayList<>();
        private final List<String> tools = new ArrayList<>();

        @Override
        public void onRoundStart(int round) {
            rounds.add(round);
        }

        @Override
        public void onText(String delta) {
            deltas.add(delta);
        }

        @Override
        public void onToolCall(String toolName) {
            tools.add(toolName);
        }
    }

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
}
