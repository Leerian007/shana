package com.shana.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shana.memory.ConversationMemory;
import com.shana.model.ChatMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 基于 Redis 的会话上下文实现：多实例部署时共享同一份上下文窗口。
 *
 * <p>定位：只保存"喂给模型的最近 N 条消息"，是<strong>热数据</strong>，不是历史存档。
 * 与 {@code JdbcConversationMemory} 的区别在于它有 TTL——会话冷下来就自动过期，
 * 不会像 PostgreSQL 那样无限累积。需要长期保留的全量历史仍应落 PostgreSQL
 * （按人统计、画像、月度总结都依赖那份数据）。
 *
 * <p>数据结构选 LIST 而不是 ZSET / Hash：
 * 消息天然有序且只追加，{@code LPUSH} 把新消息放头部是 O(1)，
 * {@code LTRIM} 一条命令就能把窗口裁到固定长度，读的时候反向遍历即为时间正序。
 * 用 ZSET 反而要自己维护 score，同毫秒消息还会撞分。
 *
 * <p>写入的三条命令不在同一个 MULTI 事务里：最坏情况是并发时多留几条消息，
 * 下一次 LTRIM 会裁掉；为此引入事务脚本（Lua）不划算。TTL 放在最后刷新，
 * 即使前两步失败也不会留下永不过期的 key。
 *
 * <p>Redis 不可用时直接抛异常，不做静默降级：上下文缺失会让助手答得莫名其妙，
 * 明确失败比悄悄丢掉记忆更容易排查。
 */
@Component
@ConditionalOnProperty(prefix = "shana.memory", name = "store", havingValue = "redis")
public class RedisConversationMemory implements ConversationMemory {

    /** key 前缀，避免和同一个 Redis 里的其它业务数据混在一起 */
    private static final String KEY_PREFIX = "shana:chat:";

    private final StringRedisTemplate redisTemplate;
    private final ChatMessageCodec codec;
    private final int maxMessages;
    private final Duration ttl;

    public RedisConversationMemory(StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper,
                                   @Value("${shana.memory.max-messages:20}") int maxMessages,
                                   @Value("${shana.memory.ttl:24h}") Duration ttl) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("shana.memory.max-messages 必须大于 0");
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("shana.memory.ttl 必须大于 0");
        }
        this.redisTemplate = redisTemplate;
        this.codec = new ChatMessageCodec(objectMapper);
        this.maxMessages = maxMessages;
        this.ttl = ttl;
    }

    @Override
    public void append(String conversationId, ChatMessage message) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(message, "message must not be null");

        String key = key(conversationId);
        // 新消息放头部，列表天然按"最新在前"排列
        redisTemplate.opsForList().leftPush(key, codec.encode(message));
        // 裁剪到窗口大小，这里保留的是头部（最新的）N 条
        redisTemplate.opsForList().trim(key, 0, maxMessages - 1);
        // 每次写入都续期：活跃会话不过期，冷下来的会话由 Redis 自己回收
        redisTemplate.expire(key, ttl);
    }

    @Override
    public List<ChatMessage> history(String conversationId) {
        List<String> raw = redisTemplate.opsForList().range(key(conversationId), 0, maxMessages - 1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        // 存储顺序是"最新在前"，翻回来才是对话顺序
        List<ChatMessage> messages = new ArrayList<>(raw.size());
        for (int i = raw.size() - 1; i >= 0; i--) {
            messages.add(codec.decode(raw.get(i)));
        }
        return List.copyOf(messages);
    }

    @Override
    public void clear(String conversationId) {
        redisTemplate.delete(key(conversationId));
    }

    private static String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
