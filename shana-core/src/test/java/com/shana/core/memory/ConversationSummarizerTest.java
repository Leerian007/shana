package com.shana.core.memory;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.memory.ArchiveMessage;
import com.shana.memory.ConversationArchive;
import com.shana.memory.FactKind;
import com.shana.memory.LongTermMemory;
import com.shana.memory.MemoryFact;
import com.shana.memory.MemorySnapshot;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRequest;
import com.shana.model.ChatResponse;
import com.shana.model.LlmClient;
import com.shana.model.LlmClientRegistry;
import com.shana.model.LlmProvider;
import com.shana.model.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 摘要编排测试：归档、长期记忆、模型全部替身，验证"什么时候做、做完写什么、出错怎么办"。
 */
class ConversationSummarizerTest {

    private static final String CONVERSATION_ID = "group-1";

    private ConversationArchive archive;
    private LongTermMemory longTermMemory;
    private LlmClient llmClient;
    private ConversationSummarizer summarizer;

    @BeforeEach
    void setUp() {
        archive = mock(ConversationArchive.class);
        longTermMemory = mock(LongTermMemory.class);
        llmClient = mock(LlmClient.class);

        LlmClientRegistry registry = mock(LlmClientRegistry.class);
        given(registry.get(LlmProvider.OPENAI_COMPATIBLE)).willReturn(llmClient);

        summarizer = new ConversationSummarizer(
                providers.of(archive), providers.of(longTermMemory), registry,
                20, 50, LlmProvider.OPENAI_COMPATIBLE);
    }

    @Test
    @DisplayName("没有归档能力（Redis / 内存模式）时安静跳过")
    void shouldSkipWhenArchiveAbsent() {
        summarizer = new ConversationSummarizer(
                providers.empty(), providers.of(longTermMemory), mock(LlmClientRegistry.class),
                20, 50, LlmProvider.OPENAI_COMPATIBLE);

        assertThat(summarizer.doSummarize(CONVERSATION_ID)).isFalse();
    }

    @Test
    @DisplayName("会话里没有消息时不调用模型")
    void shouldSkipWhenConversationEmpty() {
        given(archive.latestMessageId(CONVERSATION_ID)).willReturn(0L);

        assertThat(summarizer.doSummarize(CONVERSATION_ID)).isFalse();
        verify(llmClient, never()).chat(any());
    }

    @Test
    @DisplayName("未消化的消息不足阈值时不调用模型")
    void shouldSkipWhenBelowThreshold() {
        given(archive.latestMessageId(CONVERSATION_ID)).willReturn(10L);
        given(longTermMemory.load(CONVERSATION_ID, 0)).willReturn(new MemorySnapshot("旧摘要", 5L, List.of()));
        // 只有 5 条待消化，阈值是 20

        assertThat(summarizer.doSummarize(CONVERSATION_ID)).isFalse();
        verify(llmClient, never()).chat(any());
        verify(longTermMemory, never()).advanceSummary(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("达到阈值时调用模型并推进游标、写入事实")
    void shouldSummarizeAndAdvanceCursor() {
        given(archive.latestMessageId(CONVERSATION_ID)).willReturn(30L);
        given(longTermMemory.load(CONVERSATION_ID, 0)).willReturn(new MemorySnapshot("旧摘要", 5L, List.of()));
        given(archive.after(CONVERSATION_ID, 5L, 50)).willReturn(List.of(
                new ArchiveMessage(6L, ChatMessage.user("u-1001", "我在学 Flink")),
                new ArchiveMessage(7L, ChatMessage.assistant("加油")),
                new ArchiveMessage(8L, ChatMessage.user("u-1002", "周五一起看比赛？"))));
        given(llmClient.chat(any())).willReturn(new ChatResponse("""
                {"summary":"群里在聊 Flink 和周末看比赛","facts":[{"kind":"PERSON","subject":"","content":"有成员在学 Flink"}]}
                """, LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));
        given(longTermMemory.advanceSummary(CONVERSATION_ID, "群里在聊 Flink 和周末看比赛", 8L, 5L))
                .willReturn(true);

        assertThat(summarizer.doSummarize(CONVERSATION_ID)).isTrue();

        verify(longTermMemory).advanceSummary(CONVERSATION_ID, "群里在聊 Flink 和周末看比赛", 8L, 5L);
        verify(longTermMemory).addFacts(eq(CONVERSATION_ID),
                eq(List.of(MemoryFact.of(FactKind.PERSON, "", "有成员在学 Flink"))), eq(8L));
    }

    @Test
    @DisplayName("摘要已被其它实例推进时丢弃本次结果，不写事实")
    void shouldDiscardWhenAnotherInstanceWon() {
        given(archive.latestMessageId(CONVERSATION_ID)).willReturn(30L);
        given(longTermMemory.load(CONVERSATION_ID, 0)).willReturn(new MemorySnapshot(null, 0L, List.of()));
        given(archive.after(CONVERSATION_ID, 0L, 50)).willReturn(List.of(
                new ArchiveMessage(1L, ChatMessage.user("u-1001", "你好"))));
        given(llmClient.chat(any())).willReturn(new ChatResponse(
                "{\"summary\":\"打招呼\",\"facts\":[]}",
                LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));
        // 乐观更新没有命中：说明另一个实例已经推进过游标
        given(longTermMemory.advanceSummary(eq(CONVERSATION_ID), anyString(), anyLong(), anyLong()))
                .willReturn(false);

        assertThat(summarizer.doSummarize(CONVERSATION_ID)).isFalse();
        verify(longTermMemory, never()).addFacts(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("模型返回坏 JSON 时只记日志，不向外抛异常")
    void shouldSwallowFailure() {
        given(archive.latestMessageId(CONVERSATION_ID)).willReturn(30L);
        given(longTermMemory.load(CONVERSATION_ID, 0)).willReturn(new MemorySnapshot(null, 0L, List.of()));
        given(archive.after(CONVERSATION_ID, 0L, 50)).willReturn(List.of(
                new ArchiveMessage(1L, ChatMessage.user("u-1001", "你好"))));
        given(llmClient.chat(any())).willReturn(new ChatResponse(
                "抱歉，我无法完成", LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));

        assertThatCode(() -> summarizer.summarizeAsync(CONVERSATION_ID)).doesNotThrowAnyException();
        verify(longTermMemory, never()).advanceSummary(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("模型调用失败时不影响游标")
    void shouldNotAdvanceCursorWhenModelFails() {
        given(archive.latestMessageId(CONVERSATION_ID)).willReturn(30L);
        given(longTermMemory.load(CONVERSATION_ID, 0)).willReturn(new MemorySnapshot(null, 0L, List.of()));
        given(archive.after(CONVERSATION_ID, 0L, 50)).willReturn(List.of(
                new ArchiveMessage(1L, ChatMessage.user("u-1001", "你好"))));
        given(llmClient.chat(any())).willThrow(
                new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "上游超时"));

        assertThatCode(() -> summarizer.summarizeAsync(CONVERSATION_ID)).doesNotThrowAnyException();
        verify(longTermMemory, never()).advanceSummary(anyString(), anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("喂给模型的原文带消息 ID 与发言者，且不含 system 消息")
    void shouldRenderBatchWithIdAndSpeaker() {
        given(archive.latestMessageId(CONVERSATION_ID)).willReturn(30L);
        given(longTermMemory.load(CONVERSATION_ID, 0)).willReturn(new MemorySnapshot(null, 0L, List.of()));
        given(archive.after(CONVERSATION_ID, 0L, 50)).willReturn(List.of(
                new ArchiveMessage(11L, ChatMessage.user("u-1001", "我在学 Flink")),
                new ArchiveMessage(12L, ChatMessage.system("人设与记忆")),
                new ArchiveMessage(13L, ChatMessage.assistant("加油"))));
        given(llmClient.chat(any())).willReturn(new ChatResponse(
                "{\"summary\":\"聊了 Flink\",\"facts\":[]}",
                LlmProvider.OPENAI_COMPATIBLE, "test-model", TokenUsage.unknown()));
        given(longTermMemory.advanceSummary(anyString(), anyString(), anyLong(), anyLong())).willReturn(true);

        summarizer.doSummarize(CONVERSATION_ID);

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmClient).chat(captor.capture());
        String payload = captor.getValue().messages().get(1).content();

        assertThat(payload).contains("[11] u-1001：我在学 Flink")
                .contains("[13] Shana：加油")
                // system 是自己塞的人设，喂回去只会让模型自我复述
                .doesNotContain("人设与记忆")
                .doesNotContain("[12]");
        // 摘要用低温度，避免同一段对话每次总结出不同版本
        assertThat(captor.getValue().temperature()).isEqualTo(0.2);
    }

    /** 用 Mockito 造 ObjectProvider，只为控制"Bean 在不在"。 */
    private static final class providers {

        private providers() {
        }

        @SuppressWarnings("unchecked")
        static <T> ObjectProvider<T> of(T bean) {
            ObjectProvider<T> provider = mock(ObjectProvider.class);
            given(provider.getIfAvailable()).willReturn(bean);
            return provider;
        }

        @SuppressWarnings("unchecked")
        static <T> ObjectProvider<T> empty() {
            ObjectProvider<T> provider = mock(ObjectProvider.class);
            given(provider.getIfAvailable()).willReturn(null);
            return provider;
        }
    }
}
