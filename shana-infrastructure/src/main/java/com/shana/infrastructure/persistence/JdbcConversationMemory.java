package com.shana.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shana.infrastructure.persistence.entity.ChatMessageEntity;
import com.shana.infrastructure.persistence.mapper.ChatMessageMapper;
import com.shana.memory.ConversationMemory;
import com.shana.model.ChatMessage;
import com.shana.model.ChatRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 基于 PostgreSQL 的会话上下文实现，默认启用。
 *
 * <p>与内存实现的关键差异：<b>只限制读取条数，不删除历史数据</b>。
 * 内存版为了省内存会把超出窗口的消息丢掉，而数据库里的全量历史是后续
 * 长期记忆、群聊月度总结的原料，删掉就再也拿不回来了。
 *
 * <p>用 {@code @ConditionalOnProperty} 与 {@code InMemoryConversationMemory} 二选一：
 * 设 {@code shana.memory.store=memory} 可切回内存实现，单元测试与离线演示时用得上。
 */
@Component
@ConditionalOnProperty(prefix = "shana.memory", name = "store", havingValue = "jdbc", matchIfMissing = true)
public class JdbcConversationMemory implements ConversationMemory {

    private final ChatMessageMapper chatMessageMapper;
    private final int maxMessages;

    public JdbcConversationMemory(ChatMessageMapper chatMessageMapper,
                                  @Value("${shana.memory.max-messages:20}") int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("shana.memory.max-messages 必须大于 0");
        }
        this.chatMessageMapper = chatMessageMapper;
        this.maxMessages = maxMessages;
    }

    @Override
    public void append(String conversationId, ChatMessage message) {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(message, "message must not be null");

        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setConversationId(conversationId);
        // 枚举名转小写入库，与主流 Chat API 的角色写法一致，也避免大小写不一致导致查不出来
        entity.setRole(message.role().name().toLowerCase(Locale.ROOT));
        // 发送者只做留存：写库是为了以后能按人聚合，读出来拼 prompt 时不会被使用
        entity.setSenderId(message.senderId());
        entity.setContent(message.content());
        entity.setCreatedAt(OffsetDateTime.now());
        chatMessageMapper.insert(entity);
    }

    @Override
    public List<ChatMessage> history(String conversationId) {
        // 按主键倒序取最近 N 条：自增主键比时间戳更适合定序，同一毫秒插入也不会乱
        Page<ChatMessageEntity> page = new Page<>(1, maxMessages, false);
        List<ChatMessageEntity> rows = chatMessageMapper.selectPage(page,
                        Wrappers.<ChatMessageEntity>lambdaQuery()
                                .eq(ChatMessageEntity::getConversationId, conversationId)
                                .orderByDesc(ChatMessageEntity::getId))
                .getRecords();

        // 上面是"最近的在前"，转成对话顺序要翻回来（时间正序）
        List<ChatMessage> messages = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--) {
            messages.add(toChatMessage(rows.get(i)));
        }
        return List.copyOf(messages);
    }

    @Override
    public void clear(String conversationId) {
        chatMessageMapper.delete(Wrappers.<ChatMessageEntity>lambdaQuery()
                .eq(ChatMessageEntity::getConversationId, conversationId));
    }

    private ChatMessage toChatMessage(ChatMessageEntity entity) {
        return new ChatMessage(parseRole(entity.getRole()), entity.getSenderId(), entity.getContent());
    }

    private ChatRole parseRole(String role) {
        try {
            return ChatRole.valueOf(role.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            // 角色是入库前自己写的，出现未知值说明数据被外部改过或迁移脚本有问题，
            // 这里直接失败比静默当成 user 更安全
            throw new IllegalStateException("数据库中存在无法识别的消息角色: " + role, e);
        }
    }
}
