package com.shana.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.memory.ConversationMemory;
import com.shana.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Redis 版上下文的单元测试。
 *
 * <p>Redis 客户端用 Mockito 替身，不连真实服务——这里要验的是"我们发出的命令对不对"
 * （key 命名、窗口裁剪、TTL、翻序），而不是 Redis 本身的行为。
 * 真实读写由启动 compose 后的联调覆盖。
 */
class RedisConversationMemoryTest {

    private static final String KEY = "shana:chat:group-1";
    private static final Duration TTL = Duration.ofHours(24);

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOperations;
    private ConversationMemory memory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOperations = mock(ListOperations.class);
        given(redisTemplate.opsForList()).willReturn(listOperations);
        memory = new RedisConversationMemory(redisTemplate, new ObjectMapper(), 20, TTL);
    }

    @Test
    @DisplayName("追加消息：按会话拼 key、入头部、裁窗口、刷新 TTL")
    void appendPushesAndTrims() {
        memory.append("group-1", ChatMessage.user("u-1001", "你好"));

        verify(listOperations).leftPush(eq(KEY), anyString());
        // 保留头部 maxMessages 条，即最新的那批
        verify(listOperations).trim(KEY, 0, 19);
        verify(redisTemplate).expire(KEY, TTL);
    }

    @Test
    @DisplayName("窗口大小变化时裁剪上界跟着变")
    void trimBoundFollowsConfiguredWindow() {
        memory = new RedisConversationMemory(redisTemplate, new ObjectMapper(), 3, TTL);

        memory.append("group-1", ChatMessage.user("u-1001", "你好"));

        verify(listOperations).trim(KEY, 0, 2);
    }

    @Test
    @DisplayName("读取历史：Redis 里是最新在前，返回前翻成时间正序")
    void historyReturnsChronologicalOrder() {
        String newer = new ChatMessageCodec(new ObjectMapper()).encode(ChatMessage.assistant("第二句"));
        String older = new ChatMessageCodec(new ObjectMapper()).encode(ChatMessage.user("u-1001", "第一句"));
        given(listOperations.range(KEY, 0, 19)).willReturn(List.of(newer, older));

        List<ChatMessage> history = memory.history("group-1");

        assertThat(history).containsExactly(
                ChatMessage.user("u-1001", "第一句"),
                ChatMessage.assistant("第二句"));
    }

    @Test
    @DisplayName("会话不存在时返回空列表，不抛异常")
    void historyReturnsEmptyForUnknownConversation() {
        given(listOperations.range(KEY, 0, 19)).willReturn(List.of());

        assertThat(memory.history("group-1")).isEmpty();
    }

    @Test
    @DisplayName("清空会话只删自己那个 key")
    void clearDeletesOnlyTargetConversation() {
        memory.clear("group-1");

        verify(redisTemplate).delete(KEY);
    }

    @Test
    @DisplayName("Redis 里出现坏数据时直接失败，不静默跳过")
    void brokenPayloadFailsFast() {
        given(listOperations.range(KEY, 0, 19)).willReturn(List.of("{不是合法 JSON"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> memory.history("group-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法解析");
    }

    @Test
    @DisplayName("配置非法时构造期就失败，不带病运行")
    void rejectsInvalidConfiguration() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RedisConversationMemory(redisTemplate, new ObjectMapper(), 0, TTL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-messages");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RedisConversationMemory(redisTemplate, new ObjectMapper(), 20, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");
    }
}
