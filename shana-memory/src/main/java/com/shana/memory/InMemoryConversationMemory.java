package com.shana.memory;

import com.shana.model.ChatMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版会话上下文，用于本地开发和单元测试。
 *
 * <p>每个会话只保留最近 {@code shana.memory.max-messages} 条消息：
 * 群聊消息量大，无限制累积会让 prompt 迅速超出上下文窗口并推高成本。
 * <p>注意：进程重启后数据丢失，多实例部署时各实例不共享——同一个群在两个实例上
 * 会看到两份互不相干的上下文。需要跨实例共享时用 {@code RedisConversationMemory}
 * （{@code shana.memory.store=redis}）。
 * 默认走 PostgreSQL 实现（{@code JdbcConversationMemory}），
 * 只有显式配置 {@code shana.memory.store=memory} 时才会启用本类——
 * 单元测试、离线演示、以及数据库暂时不可用时用得上。
 */
@Component
@ConditionalOnProperty(prefix = "shana.memory", name = "store", havingValue = "memory")
public class InMemoryConversationMemory implements ConversationMemory {

    private final Map<String, Deque<ChatMessage>> conversations = new ConcurrentHashMap<>();
    private final int maxMessages;

    public InMemoryConversationMemory(@Value("${shana.memory.max-messages:20}") int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("shana.memory.max-messages 必须大于 0");
        }
        this.maxMessages = maxMessages;
    }

    @Override
    public void append(String conversationId, ChatMessage message) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(message, "message must not be null");

        Deque<ChatMessage> messages = conversations.computeIfAbsent(conversationId, key -> new ArrayDeque<>());
        // ArrayDeque 非线程安全，同一会话的读写需要串行化
        synchronized (messages) {
            messages.addLast(message);
            while (messages.size() > maxMessages) {
                messages.removeFirst();
            }
        }
    }

    @Override
    public List<ChatMessage> history(String conversationId) {
        Deque<ChatMessage> messages = conversations.get(conversationId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    @Override
    public void clear(String conversationId) {
        conversations.remove(conversationId);
    }
}
