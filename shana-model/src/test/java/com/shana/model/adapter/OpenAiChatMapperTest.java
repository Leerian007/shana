package com.shana.model.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.LlmProvider;
import com.shana.model.TokenUsage;
import com.shana.model.ToolCall;
import com.shana.model.ToolSpec;
import com.shana.model.adapter.dto.OpenAiChatMessage;
import com.shana.model.adapter.dto.OpenAiChatRequest;
import com.shana.model.adapter.dto.OpenAiChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 协议转换测试。全部是纯函数调用，不碰网络、不启 Spring 容器。
 *
 * <p>这里刻意使用**严格模式**的 {@link ObjectMapper}（不关闭"未知字段报错"），
 * 以此证明 DTO 上的 {@code @JsonIgnoreProperties} 是真的生效，而不是靠全局配置兜着。
 */
class OpenAiChatMapperTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 取自真实上游的响应样本，含本项目用不到的 id/object/index/finish_reason/total_tokens。 */
    private static final String REAL_RESPONSE = """
            {
              "id": "chatcmpl-8f2c",
              "object": "chat.completion",
              "created": 1730000000,
              "model": "deepseek-chat",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "你好，我是 Shana。" },
                  "finish_reason": "stop"
                }
              ],
              "usage": { "prompt_tokens": 12, "completion_tokens": 34, "total_tokens": 46 }
            }
            """;

    @Test
    @DisplayName("ChatRequest 翻译成上游请求：模型、温度、消息顺序与角色名")
    void shouldMapChatRequestToOpenAiRequest() {
        ChatRequest request = new ChatRequest(
                LlmProvider.OPENAI_COMPATIBLE,
                "deepseek-chat",
                List.of(
                        ChatMessage.system("你是群聊助手"),
                        ChatMessage.user("今天聊点什么"),
                        ChatMessage.assistant("聊聊 RAG 吧")),
                0.3,
                256);

        OpenAiChatRequest mapped = OpenAiChatMapper.toRequest(request, defaults());

        assertThat(mapped.model()).isEqualTo("deepseek-chat");
        assertThat(mapped.temperature()).isEqualTo(0.3);
        assertThat(mapped.maxTokens()).isEqualTo(256);
        assertThat(mapped.messages())
                .extracting(OpenAiChatMessage::role, OpenAiChatMessage::content)
                .containsExactly(
                        tuple("system", "你是群聊助手"),
                        tuple("user", "今天聊点什么"),
                        tuple("assistant", "聊聊 RAG 吧"));
    }

    @Test
    @DisplayName("请求未指定模型和温度时回落到配置默认值")
    void shouldFallBackToConfiguredDefaults() {
        ChatRequest request = new ChatRequest(
                LlmProvider.OPENAI_COMPATIBLE, null, List.of(ChatMessage.user("hi")), null, null);

        OpenAiChatRequest mapped = OpenAiChatMapper.toRequest(request, defaults());

        assertThat(mapped.model()).isEqualTo("default-model");
        assertThat(mapped.temperature()).isEqualTo(0.7);
        assertThat(mapped.maxTokens()).isNull();
    }

    @Test
    @DisplayName("序列化后的请求体：messages 是对象数组，null 字段不出现")
    void shouldSerializeMessagesAsObjectArrayAndOmitNulls() throws Exception {
        OpenAiChatRequest request =
                new OpenAiChatRequest("deepseek-chat", List.of(new OpenAiChatMessage("user", "你好", null, null)),
                        null, null, null);

        JsonNode body = JSON.readTree(JSON.writeValueAsString(request));

        assertThat(body.get("messages").isArray()).isTrue();
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(body.get("messages").get(0).get("content").asText()).isEqualTo("你好");
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("max_tokens")).isFalse();
    }

    @Test
    @DisplayName("解析真实响应：取出 content 与 token 用量")
    void shouldParseRealResponse() throws Exception {
        OpenAiChatResponse response = JSON.readValue(REAL_RESPONSE, OpenAiChatResponse.class);

        ChatResponse mapped = OpenAiChatMapper.toChatResponse(response, "deepseek-chat");

        assertThat(mapped.content()).isEqualTo("你好，我是 Shana。");
        assertThat(mapped.provider()).isEqualTo(LlmProvider.OPENAI_COMPATIBLE);
        assertThat(mapped.model()).isEqualTo("deepseek-chat");
        assertThat(mapped.usage()).isEqualTo(new TokenUsage(12, 34));
        assertThat(mapped.usage().totalTokens()).isEqualTo(46);
    }

    @Test
    @DisplayName("上游未返回 usage 时用量降级为 unknown")
    void shouldUseUnknownUsageWhenUsageMissing() throws Exception {
        OpenAiChatResponse response = JSON.readValue("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}
                """, OpenAiChatResponse.class);

        ChatResponse mapped = OpenAiChatMapper.toChatResponse(response, "deepseek-chat");

        assertThat(mapped.usage()).isEqualTo(TokenUsage.unknown());
    }

    @Test
    @DisplayName("choices 为空时翻译成服务不可用异常")
    void shouldRejectEmptyChoices() {
        OpenAiChatResponse response = new OpenAiChatResponse(List.of(), null);

        assertThatThrownBy(() -> OpenAiChatMapper.toChatResponse(response, "deepseek-chat"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未返回任何回复")
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("响应体缺失 content 时翻译成服务不可用异常")
    void shouldRejectMissingContent() {
        OpenAiChatResponse response = new OpenAiChatResponse(
                List.of(new OpenAiChatResponse.OpenAiChoice(new OpenAiChatMessage("assistant", null, null, null))), null);

        assertThatThrownBy(() -> OpenAiChatMapper.toChatResponse(response, "deepseek-chat"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少内容")
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Test
    @DisplayName("响应体为 null 时翻译成服务不可用异常")
    void shouldRejectNullResponse() {
        assertThatThrownBy(() -> OpenAiChatMapper.toChatResponse(null, "deepseek-chat"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }


    @Test
    @DisplayName("工具声明：type 固定为 function，parameters 嵌成对象而不是转义字符串")
    void shouldSerializeToolSpecAsFunctionObject() throws Exception {
        ChatRequest request = new ChatRequest(LlmProvider.OPENAI_COMPATIBLE, "deepseek-chat",
                List.of(ChatMessage.user("谁最活跃")), null, null,
                List.of(new ToolSpec("group_message_stats", "统计发言条数", """
                        {"type":"object","properties":{"days":{"type":"integer"}}}
                        """)));

        JsonNode body = JSON.readTree(JSON.writeValueAsString(OpenAiChatMapper.toRequest(request, defaults())));
        JsonNode tool = body.get("tools").get(0);

        assertThat(tool.get("type").asText()).isEqualTo("function");
        assertThat(tool.get("function").get("name").asText()).isEqualTo("group_message_stats");
        // 关键：parameters 必须是对象。写成字符串会被上游当成非法 schema
        assertThat(tool.get("function").get("parameters").isObject()).isTrue();
        assertThat(tool.get("function").get("parameters").get("type").asText()).isEqualTo("object");
    }

    @Test
    @DisplayName("没有工具时请求体里不出现 tools 字段")
    void shouldOmitToolsWhenEmpty() throws Exception {
        ChatRequest request = ChatRequest.of(LlmProvider.OPENAI_COMPATIBLE, "deepseek-chat",
                List.of(ChatMessage.user("hi")));

        JsonNode body = JSON.readTree(JSON.writeValueAsString(OpenAiChatMapper.toRequest(request, defaults())));

        assertThat(body.has("tools")).isFalse();
    }

    /** 真实 DeepSeek/OpenAI 风格的工具调用响应：content 为 null，tool_calls 里有 args 字符串。 */
    private static final String TOOL_CALL_RESPONSE = """
            {
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_abc",
                    "type": "function",
                    "function": { "name": "group_message_stats", "arguments": "{\\"days\\":7}" }
                  }]
                },
                "finish_reason": "tool_calls"
              }],
              "usage": { "prompt_tokens": 30, "completion_tokens": 12, "total_tokens": 42 }
            }
            """;

    @Test
    @DisplayName("解析工具调用响应：content 为空不算错误，tool_calls 被翻译成内部模型")
    void shouldParseToolCallResponse() throws Exception {
        OpenAiChatResponse response = JSON.readValue(TOOL_CALL_RESPONSE, OpenAiChatResponse.class);

        ChatResponse mapped = OpenAiChatMapper.toChatResponse(response, "deepseek-chat");

        assertThat(mapped.content()).isNull();
        assertThat(mapped.hasToolCalls()).isTrue();
        assertThat(mapped.toolCalls()).containsExactly(
                new ToolCall("call_abc", "group_message_stats", "{\"days\":7}"));
        assertThat(mapped.usage()).isEqualTo(new TokenUsage(30, 12));
    }

    @Test
    @DisplayName("回传工具往返：assistant 带 tool_calls，tool 消息带 tool_call_id，content 该省的省")
    void shouldSerializeToolRoundTrip() throws Exception {
        ChatMessage toolTurn = ChatMessage.toolResult("call_abc", "u-1001：12 条");
        ChatMessage assistantTurn = ChatMessage.assistantToolCalls(null,
                List.of(new ToolCall("call_abc", "group_message_stats", "{\"days\":7}")));
        ChatRequest request = ChatRequest.of(LlmProvider.OPENAI_COMPATIBLE, "deepseek-chat",
                List.of(ChatMessage.user("谁最活跃"), assistantTurn, toolTurn));

        JsonNode messages = JSON.readTree(
                JSON.writeValueAsString(OpenAiChatMapper.toRequest(request, defaults()))).get("messages");

        JsonNode assistant = messages.get(1);
        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        // content 为 null 时必须整个字段消失，部分兼容服务不接受 "content": null
        assertThat(assistant.has("content")).isFalse();
        assertThat(assistant.get("tool_calls").get(0).get("id").asText()).isEqualTo("call_abc");
        assertThat(assistant.get("tool_calls").get(0).get("type").asText()).isEqualTo("function");
        assertThat(assistant.get("tool_calls").get(0).get("function").get("arguments").asText())
                .isEqualTo("{\"days\":7}");

        JsonNode tool = messages.get(2);
        assertThat(tool.get("role").asText()).isEqualTo("tool");
        assertThat(tool.get("tool_call_id").asText()).isEqualTo("call_abc");
        assertThat(tool.get("content").asText()).isEqualTo("u-1001：12 条");
    }

    @Test
    @DisplayName("工具参数为空字符串时统一成空对象，避免工具自己判空")
    void shouldNormalizeBlankArguments() {
        assertThat(new ToolCall("c1", "echo", " ").arguments()).isEqualTo("{}");
        assertThat(new ToolCall("c1", "echo", null).arguments()).isEqualTo("{}");
    }

    private static OpenAiCompatibleProperties defaults() {
        return new OpenAiCompatibleProperties(
                "https://api.deepseek.com/v1", "test-key", "default-model", 0.7, null);
    }
}
