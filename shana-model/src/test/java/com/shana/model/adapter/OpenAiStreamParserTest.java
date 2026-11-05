package com.shana.model.adapter;

import com.shana.common.exception.BusinessException;
import com.shana.model.ChatResponse;
import com.shana.model.LlmProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 流式帧解析测试。
 *
 * <p>没有网络、没有模型：直接喂协议原文。这里覆盖的都是"靠肉眼看代码看不出来、
 * 上线后才会表现为答案错乱"的地方——尤其是工具调用碎片按 index 归组。
 */
class OpenAiStreamParserTest {

    private static final LlmProvider PROVIDER = LlmProvider.OPENAI_COMPATIBLE;

    private final List<String> deltas = new ArrayList<>();

    @Test
    @DisplayName("正文增量按到达顺序拼接，[DONE] 之后的数据被忽略")
    void shouldJoinTextDeltas() {
        OpenAiStreamParser parser = new OpenAiStreamParser(deltas::add);

        parser.accept(textFrame("你"));
        parser.accept("");
        parser.accept(": keep-alive");
        parser.accept("event: message");
        parser.accept(textFrame("好"));
        parser.accept("data: [DONE]");
        parser.accept(textFrame("这段不该被接收"));

        assertThat(deltas).containsExactly("你", "好");
        assertThat(parser.isDone()).isTrue();
        assertThat(parser.toResponse("m", PROVIDER).content()).isEqualTo("你好");
    }

    @Test
    @DisplayName("首帧只有 role、content 为空：不计入增量，也不报错")
    void shouldIgnoreEmptyContent() {
        OpenAiStreamParser parser = new OpenAiStreamParser(deltas::add);

        parser.accept(frame("{\"role\":\"assistant\",\"content\":\"\"}"));
        parser.accept(textFrame("在"));

        assertThat(deltas).containsExactly("在");
        assertThat(parser.toResponse("m", PROVIDER).content()).isEqualTo("在");
    }

    @Test
    @DisplayName("同一轮两个工具调用：按 index 分别归组，参数碎片按顺序拼接")
    void shouldAssembleFragmentToolCallsByIndex() {
        OpenAiStreamParser parser = new OpenAiStreamParser(deltas::add);

        // 真实上游的顺序：先并发下发两个调用的头部，再分别下发各自的参数碎片
        parser.accept(toolStartFrame(0, "call_1", "group_message_stats"));
        parser.accept(toolStartFrame(1, "call_2", "echo"));
        parser.accept(toolArgsFrame(0, "{\"days\":"));
        parser.accept(toolArgsFrame(1, "{\"text\":\"hi\"}"));
        parser.accept(toolArgsFrame(0, "7}"));
        parser.accept("data: [DONE]");

        ChatResponse response = parser.toResponse("m", PROVIDER);

        assertThat(response.content()).isNull();
        assertThat(response.toolCalls()).hasSize(2);
        assertThat(response.toolCalls().get(0).id()).isEqualTo("call_1");
        assertThat(response.toolCalls().get(0).name()).isEqualTo("group_message_stats");
        assertThat(response.toolCalls().get(0).arguments()).isEqualTo("{\"days\":7}");
        assertThat(response.toolCalls().get(1).id()).isEqualTo("call_2");
        assertThat(response.toolCalls().get(1).arguments()).isEqualTo("{\"text\":\"hi\"}");
    }

    @Test
    @DisplayName("id 与 name 每片都重发时不会重复拼接")
    void shouldNotDuplicateRepeatedIdAndName() {
        OpenAiStreamParser parser = new OpenAiStreamParser(deltas::add);

        parser.accept(toolStartFrame(0, "call_1", "echo"));
        // 有的服务会在每个参数分片里重复带上 id 与 name
        parser.accept("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":"
                + "[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"echo\",\"arguments\":\"{}\"}}]}}]}");

        ChatResponse response = parser.toResponse("m", PROVIDER);

        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().getFirst().name()).isEqualTo("echo");
        assertThat(response.toolCalls().getFirst().arguments()).isEqualTo("{}");
    }

    @Test
    @DisplayName("收尾帧的 choices 是空数组，但 usage 必须被读到")
    void shouldReadUsageFromChoicesEmptyFrame() {
        OpenAiStreamParser parser = new OpenAiStreamParser(deltas::add);

        parser.accept(textFrame("答"));
        parser.accept("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":34}}");

        ChatResponse response = parser.toResponse("m", PROVIDER);

        assertThat(response.content()).isEqualTo("答");
        assertThat(response.usage().promptTokens()).isEqualTo(12);
        assertThat(response.usage().completionTokens()).isEqualTo(34);
    }

    @Test
    @DisplayName("一边说话一边调工具：正文与工具调用同时保留")
    void shouldKeepBothContentAndToolCalls() {
        OpenAiStreamParser parser = new OpenAiStreamParser(deltas::add);

        parser.accept("");
        parser.accept("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"我查一下\"}}]}");
        parser.accept(toolStartFrame(0, "call_1", "echo"));
        parser.accept(toolArgsFrame(0, "{}"));

        ChatResponse response = parser.toResponse("m", PROVIDER);

        assertThat(response.content()).isEqualTo("我查一下");
        assertThat(response.hasToolCalls()).isTrue();
    }

    @Test
    @DisplayName("没有参数的调用：arguments 补成空对象，而不是 null")
    void shouldDefaultEmptyArguments() {
        OpenAiStreamParser parser = new OpenAiStreamParser(deltas::add);

        parser.accept(toolStartFrame(0, "call_1", "now"));

        assertThat(parser.toResponse("m", PROVIDER).toolCalls().getFirst().arguments()).isEqualTo("{}");
    }

    @Test
    @DisplayName("流中下发的 error 帧转成业务异常，不能当成正常结束")
    void shouldThrowOnErrorFrame() {
        OpenAiStreamParser parser = new OpenAiStreamParser(deltas::add);

        assertThatThrownBy(() -> parser.accept("data: {\"error\":{\"message\":\"rate limit exceeded\"}}"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rate limit exceeded");
    }

    @Test
    @DisplayName("数据帧不是合法 JSON 时明确报错，而不是静默丢弃")
    void shouldThrowOnMalformedJson() {
        OpenAiStreamParser parser = new OpenAiStreamParser(deltas::add);

        assertThatThrownBy(() -> parser.accept("data: {\"choices\":"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是合法 JSON");
    }

    @Test
    @DisplayName("整段流什么都没有：报未返回内容，避免上层拿到空回复")
    void shouldThrowWhenNothingReceived() {
        OpenAiStreamParser parser = new OpenAiStreamParser(deltas::add);
        parser.accept("data: [DONE]");

        assertThatThrownBy(() -> parser.toResponse("m", PROVIDER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未返回任何内容");
    }

    // ---------- 构造协议原文 ----------

    private static String frame(String deltaJson) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":" + deltaJson + "}]}";
    }

    private static String textFrame(String text) {
        return frame("{\"content\":\"" + escape(text) + "\"}");
    }

    private static String toolStartFrame(int index, String id, String name) {
        return frame("{\"tool_calls\":[{\"index\":" + index + ",\"id\":\"" + id
                + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"\"}}]}");
    }

    private static String toolArgsFrame(int index, String fragment) {
        return frame("{\"tool_calls\":[{\"index\":" + index + ",\"function\":{\"arguments\":\""
                + escape(fragment) + "\"}}]}");
    }

    /** JSON 字符串内部的转义，避免测试里出现四层反斜杠。 */
    private static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
