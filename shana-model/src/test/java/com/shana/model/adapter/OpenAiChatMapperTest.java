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
                new OpenAiChatRequest("deepseek-chat", List.of(new OpenAiChatMessage("user", "你好")), null, null);

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
                List.of(new OpenAiChatResponse.OpenAiChoice(new OpenAiChatMessage("assistant", null))), null);

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

    private static OpenAiCompatibleProperties defaults() {
        return new OpenAiCompatibleProperties(
                "https://api.deepseek.com/v1", "test-key", "default-model", 0.7);
    }
}
