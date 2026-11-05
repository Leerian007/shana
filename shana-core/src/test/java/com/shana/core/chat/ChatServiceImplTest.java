package com.shana.core.chat;

import com.shana.agent.Agent;
import com.shana.agent.ToolCallingAgent;
import com.shana.agent.tool.Tool;
import com.shana.agent.tool.ToolContext;
import com.shana.agent.tool.ToolRegistry;
import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.core.memory.ConversationSummarizer;
import com.shana.memory.ConversationMemory;
import com.shana.memory.FactKind;
import com.shana.memory.LongTermMemory;
import com.shana.memory.MemoryFact;
import com.shana.memory.MemorySnapshot;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.ChatRole;
import com.shana.model.LlmClient;
import com.shana.model.LlmClientRegistry;
import com.shana.model.LlmProvider;
import com.shana.model.TokenUsage;
import com.shana.model.ToolCall;
import com.shana.rag.KnowledgeBase;
import com.shana.rag.store.RetrievedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 编排逻辑测试：记忆和模型都用 Mockito 替身，不碰真实存储、不发网络请求。
 *
 * <p>重点验证四件事：prompt 的组装顺序、长期记忆的位置、上下文何时写入、失败时留下什么痕迹。
 */
class ChatServiceImplTest {

    private static final String CONVERSATION_ID = "group-1";

    private ConversationMemory memory;
    private LlmClient llmClient;
    private LlmClientRegistry registry;
    private Agent agent;
    private ChatServiceImpl chatService;

    @BeforeEach
    void setUp() {
        memory = mock(ConversationMemory.class);
        llmClient = mock(LlmClient.class);
        registry = mock(LlmClientRegistry.class);
        given(registry.get(LlmProvider.OPENAI_COMPATIBLE)).willReturn(llmClient);
        // 用真实 Agent + 空工具表：既覆盖"没有工具时就是一次普通调用"，又不必在测试里复述编排逻辑
        agent = new ToolCallingAgent(registry, new ToolRegistry(List.of()), true, 4);
        chatService = new ChatServiceImpl(memory, agent, absent(), absent(), absent(), LlmProvider.OPENAI_COMPATIBLE, true);
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

    @Test
    @DisplayName("有长期记忆时作为独立 system 消息插在历史之前")
    void shouldInjectLongTermMemoryBeforeHistory() {
        LongTermMemory longTermMemory = mock(LongTermMemory.class);
        given(longTermMemory.load(CONVERSATION_ID, 10)).willReturn(new MemorySnapshot(
                "群里在讨论 Flink 的状态后端",
                5L,
                List.of(MemoryFact.of(FactKind.PERSON, "张三", "在学 Flink"))));
        chatService = new ChatServiceImpl(memory, agent, present(longTermMemory), absent(), absent(),
                LlmProvider.OPENAI_COMPATIBLE, true);

        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("那 checkpoint 呢")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("可以用 RocksDB", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        chatService.reply(CONVERSATION_ID, "那 checkpoint 呢");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmClient).chat(captor.capture());
        List<ChatMessage> sent = captor.getValue().messages();

        // 人设 → 长期记忆 → 历史：记忆必须落在历史之前，否则模型会把它当成"刚刚发生的事"
        assertThat(sent).hasSize(3);
        assertThat(sent.get(1).role()).isEqualTo(ChatRole.SYSTEM);
        assertThat(sent.get(1).content()).contains("张三", "Flink", "状态后端");
        assertThat(sent.get(2).content()).isEqualTo("那 checkpoint 呢");
    }

    @Test
    @DisplayName("没有任何记忆时不额外插入空的 system 段落")
    void shouldNotInjectEmptyMemoryBlock() {
        LongTermMemory longTermMemory = mock(LongTermMemory.class);
        given(longTermMemory.load(CONVERSATION_ID, 10)).willReturn(MemorySnapshot.empty());
        chatService = new ChatServiceImpl(memory, agent, present(longTermMemory), absent(), absent(),
                LlmProvider.OPENAI_COMPATIBLE, true);

        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("你好")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("你好", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        chatService.reply(CONVERSATION_ID, "你好");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmClient).chat(captor.capture());
        assertThat(captor.getValue().messages()).hasSize(2);
    }

    @Test
    @DisplayName("回复成功后触发一次后台摘要")
    void shouldTriggerSummaryAfterReply() {
        ConversationSummarizer summarizer = mock(ConversationSummarizer.class);
        chatService = new ChatServiceImpl(memory, agent, absent(), present(summarizer), absent(),
                LlmProvider.OPENAI_COMPATIBLE, true);

        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("你好")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("你好", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        chatService.reply(CONVERSATION_ID, "你好");

        verify(summarizer).summarizeAsync(CONVERSATION_ID);
    }

    @Test
    @DisplayName("摘要任务提交失败不影响本次回复")
    void shouldSurviveSummarySchedulingFailure() {
        ConversationSummarizer summarizer = mock(ConversationSummarizer.class);
        willThrow(new IllegalStateException("队列满了")).given(summarizer).summarizeAsync(CONVERSATION_ID);
        chatService = new ChatServiceImpl(memory, agent, absent(), present(summarizer), absent(),
                LlmProvider.OPENAI_COMPATIBLE, true);

        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("你好")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("你好", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        ChatReply reply = chatService.reply(CONVERSATION_ID, "你好");

        assertThat(reply.content()).isEqualTo("你好");
    }

    @Test
    @DisplayName("有检索结果时作为独立 system 消息插在历史之前")
    void shouldInjectRetrievedChunksBeforeHistory() {
        KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);
        given(knowledgeBase.search("checkpoint 怎么配", 4)).willReturn(List.of(
                new RetrievedChunk(11L, 1L, "Flink 运维手册", "checkpoint 需要先配置 state backend", 0.82)));
        chatService = new ChatServiceImpl(memory, agent, absent(), absent(), present(knowledgeBase),
                LlmProvider.OPENAI_COMPATIBLE, true);

        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("checkpoint 怎么配")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("先配 state backend", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        chatService.reply(CONVERSATION_ID, "checkpoint 怎么配");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmClient).chat(captor.capture());
        List<ChatMessage> sent = captor.getValue().messages();

        // 人设 → 参考资料 → 历史：资料属于"背景知识"，混进历史会被模型当成别人刚说过的话
        assertThat(sent).hasSize(3);
        assertThat(sent.get(1).role()).isEqualTo(ChatRole.SYSTEM);
        assertThat(sent.get(1).content()).contains("参考资料", "Flink 运维手册", "state backend");
        assertThat(sent.get(2).content()).isEqualTo("checkpoint 怎么配");
    }

    @Test
    @DisplayName("检索到空结果时不插入空的参考段落")
    void shouldNotInjectEmptyKnowledgeBlock() {
        KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);
        given(knowledgeBase.search(anyString(), anyInt())).willReturn(List.of());
        chatService = new ChatServiceImpl(memory, agent, absent(), absent(), present(knowledgeBase),
                LlmProvider.OPENAI_COMPATIBLE, true);

        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("你好")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("你好", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        chatService.reply(CONVERSATION_ID, "你好");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmClient).chat(captor.capture());
        assertThat(captor.getValue().messages()).hasSize(2);
    }

    @Test
    @DisplayName("知识库检索失败不影响本次回复")
    void shouldSurviveKnowledgeSearchFailure() {
        KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);
        given(knowledgeBase.search(anyString(), anyInt()))
                .willThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "向量化服务超时"));
        chatService = new ChatServiceImpl(memory, agent, absent(), absent(), present(knowledgeBase),
                LlmProvider.OPENAI_COMPATIBLE, true);

        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("你好")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("你好", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        ChatReply reply = chatService.reply(CONVERSATION_ID, "你好");

        assertThat(reply.content()).isEqualTo("你好");
    }

    @Test
    @DisplayName("显式关闭 RAG 后完全不查知识库")
    void shouldSkipKnowledgeSearchWhenDisabled() {
        KnowledgeBase knowledgeBase = mock(KnowledgeBase.class);
        chatService = new ChatServiceImpl(memory, agent, absent(), absent(), present(knowledgeBase),
                LlmProvider.OPENAI_COMPATIBLE, false);

        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("你好")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("你好", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        chatService.reply(CONVERSATION_ID, "你好");

        verify(knowledgeBase, never()).search(anyString(), anyInt());
    }

    @Test
    @DisplayName("模型调工具时：工具往返不写进记忆，回复里带回工具轨迹与累加用量")
    void shouldExposeToolUsageWithoutPersistingToolMessages() {
        ToolRegistry tools = new ToolRegistry(List.of(new FixedResultTool("group_message_stats", "u-1001：12 条")));
        agent = new ToolCallingAgent(registry, tools, true, 4);
        chatService = new ChatServiceImpl(memory, agent, absent(), absent(), absent(),
                LlmProvider.OPENAI_COMPATIBLE, true);

        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("群里谁最活跃")));
        given(llmClient.chat(any()))
                .willReturn(new ChatResponse(null, LlmProvider.OPENAI_COMPATIBLE, "test-model",
                        new TokenUsage(10, 2), List.of(new ToolCall("call-1", "group_message_stats", "{}"))))
                .willReturn(new ChatResponse("u-1001 最活跃，12 条", LlmProvider.OPENAI_COMPATIBLE,
                        "test-model", new TokenUsage(20, 5)));

        ChatReply reply = chatService.reply(CONVERSATION_ID, "u-1001", "群里谁最活跃");

        assertThat(reply.content()).isEqualTo("u-1001 最活跃，12 条");
        assertThat(reply.toolsUsed()).containsExactly("group_message_stats");
        // 两次模型调用的用量都要算进来，只报最后一轮会低估成本
        assertThat(reply.usage().totalTokens()).isEqualTo(37);
        // 记忆里只有用户消息和最终回复：tool 消息脱离 tool_calls 就无法被上游接受
        verify(memory, times(2)).append(eq(CONVERSATION_ID), any(ChatMessage.class));
        verify(memory).append(CONVERSATION_ID, ChatMessage.assistant("u-1001 最活跃，12 条"));
    }

    @Test
    @DisplayName("没有注册任何工具时，请求里根本不带 tools 字段")
    void shouldNotSendToolsWhenNoneRegistered() {
        given(memory.history(CONVERSATION_ID)).willReturn(List.of(ChatMessage.user("你好")));
        given(llmClient.chat(any())).willReturn(
                new ChatResponse("你好", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        chatService.reply(CONVERSATION_ID, "你好");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmClient).chat(captor.capture());
        assertThat(captor.getValue().hasTools()).isFalse();
    }

    /** 固定返回一段文本的工具替身，用来验证"工具结果确实被回填给模型"。 */
    private record FixedResultTool(String name, String result) implements Tool {

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试用工具";
        }

        @Override
        public String execute(ToolContext context, String arguments) {
            return result;
        }
    }

    /** 模拟容器里没有这个 Bean 的情况（Redis / 内存模式下长期记忆不存在）。 */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(null);
        return provider;
    }

    private static <T> ObjectProvider<T> present(T bean) {
        return new FixedObjectProvider<>(bean);
    }

    /** 只关心"有没有 Bean"的最小 ObjectProvider，避免为一个测试写一堆无意义的 stub。 */
    private record FixedObjectProvider<T>(T bean) implements ObjectProvider<T> {

        @Override
        public T getObject() {
            return bean;
        }

        @Override
        public T getObject(Object... args) {
            return bean;
        }

        @Override
        public T getIfAvailable() {
            return bean;
        }

        @Override
        public T getIfUnique() {
            return bean;
        }

        @Override
        public Iterator<T> iterator() {
            return List.of(bean).iterator();
        }

        @Override
        public Stream<T> stream() {
            return Stream.of(bean);
        }
    }
}
