package com.shana.core.chat;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.memory.ConversationMemory;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.ChatRole;
import com.shana.model.LlmClient;
import com.shana.model.LlmClientRegistry;
import com.shana.model.LlmProvider;
import com.shana.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 编排逻辑测试：记忆和模型都用 Mockito 替身，不碰真实存储、不发网络请求。
 *
 * <p>重点验证三件事：prompt 的组装顺序、上下文何时写入、失败时留下什么痕迹。
 */
class ChatServiceImplTest {

    private static final String CONVERSATION_ID = "group-1";

    private ConversationMemory memory;
    private LlmClient llmClient;
    private LlmClientRegistry registry;
    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        memory = mock(ConversationMemory.class);
        llmClient = mock(LlmClient.class);
        registry = mock(LlmClientRegistry.class);
        given(registry.get(LlmProvider.OPENAI_COMPATIBLE)).willReturn(llmClient);
        chatService = new ChatServiceImpl(memory, registry, LlmProvider.OPENAI_COMPATIBLE);
    }

    @Test
    @DisplayName("发送者标识随消息写入上下文，供后续按人统计")
    void shouldPersistSenderIdWithUserMessage() {
        given(memory.history(CONVERSATION_ID)).willReturn(List.of());
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("收到", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        chatService.reply(CONVERSATION_ID, "u-1001", "你好");

        // 落库侧带上了发送者
        verify(memory).append(CONVERSATION_ID, ChatMessage.user("u-1001", "你好"));
        // 助手回复没有发送者概念
        verify(memory).append(CONVERSATION_ID, ChatMessage.assistant("收到"));
    }

    @Test
    @DisplayName("按 system + 历史正序组装 prompt，并回写助手回复")
    void shouldAssemblePromptInOrderAndWriteBack() {
        given(memory.history(CONVERSATION_ID)).willReturn(List.of(
                ChatMessage.user("第一句"),
                ChatMessage.assistant("第一句的回复"),
                ChatMessage.user("第二句")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("第二句的回复", LlmProvider.OPENAI_COMPATIBLE, "test-model", new TokenUsage(10, 20)));

        ChatReply reply = chatService.reply(CONVERSATION_ID, "第二句");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmClient).chat(captor.capture());
        List<ChatMessage> sent = captor.getValue().messages();

        assertThat(sent).hasSize(4);
        assertThat(sent.get(0).role()).isEqualTo(ChatRole.SYSTEM);
        assertThat(sent.get(0).content()).contains("Shana");
        assertThat(sent.subList(1, 4))
                .extracting(ChatMessage::content)
                .containsExactly("第一句", "第一句的回复", "第二句");
        // 模型与温度交给适配器决定，core 不写死厂商参数
        assertThat(captor.getValue().model()).isNull();
        assertThat(captor.getValue().temperature()).isNull();

        assertThat(reply.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(reply.content()).isEqualTo("第二句的回复");
        assertThat(reply.usage()).isEqualTo(new TokenUsage(10, 20));
    }

    @Test
    @DisplayName("写入顺序：先存用户消息，再读历史，再调模型，最后存回复")
    void shouldKeepSideEffectOrder() {
        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("你好")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("你好呀", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        chatService.reply(CONVERSATION_ID, "你好");

        InOrder inOrder = inOrder(memory, llmClient);
        inOrder.verify(memory).append(CONVERSATION_ID, ChatMessage.user("你好"));
        inOrder.verify(memory).history(CONVERSATION_ID);
        inOrder.verify(llmClient).chat(any());
        inOrder.verify(memory).append(CONVERSATION_ID, ChatMessage.assistant("你好呀"));
    }

    @Test
    @DisplayName("首次对话时 prompt 只有 system 和当前用户消息")
    void shouldSendSystemAndCurrentMessageForFirstTurn() {
        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("你好")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("你好呀", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        chatService.reply(CONVERSATION_ID, "你好");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmClient).chat(captor.capture());
        assertThat(captor.getValue().messages()).hasSize(2);
    }

    @Test
    @DisplayName("模型调用失败时用户消息保留、助手回复不写入")
    void shouldKeepUserMessageWhenModelFails() {
        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("你好")));
        given(llmClient.chat(any()))
                .willThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "上游挂了"));

        assertThatThrownBy(() -> chatService.reply(CONVERSATION_ID, "你好"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上游挂了");

        verify(memory).append(eq(CONVERSATION_ID), argThat(message -> message.role() == ChatRole.USER));
        verify(memory, never()).append(anyString(), argThat(message -> message.role() == ChatRole.ASSISTANT));
    }
}
