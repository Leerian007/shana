package com.shana.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shana.infrastructure.persistence.entity.ChatMessageEntity;
import com.shana.infrastructure.persistence.mapper.ChatMessageMapper;
import com.shana.memory.ArchiveMessage;
import com.shana.memory.ConversationArchive;
import com.shana.model.ChatMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 chat_message 表的归档读取。
 *
 * <p>只做两件事：拿最大消息 ID（摘要游标的上界）、按游标往后取一批原文。
 * 不做全文检索、不做时间范围查询——那是后续 RAG 与月度统计的事，
 * 现在只需要"能按顺序把没读过的消息读出来"。
 */
@Component
@ConditionalOnProperty(prefix = "shana.memory", name = "store", havingValue = "jdbc", matchIfMissing = true)
public class JdbcMessageArchive implements ConversationArchive {

    private final ChatMessageMapper chatMessageMapper;

    public JdbcMessageArchive(ChatMessageMapper chatMessageMapper) {
        this.chatMessageMapper = chatMessageMapper;
    }

    @Override
    public long latestMessageId(String conversationId) {
        Page<ChatMessageEntity> page = new Page<>(1, 1, false);
        List<ChatMessageEntity> rows = chatMessageMapper.selectPage(page,
                        Wrappers.<ChatMessageEntity>lambdaQuery()
                                .eq(ChatMessageEntity::getConversationId, conversationId)
                                .orderByDesc(ChatMessageEntity::getId))
                .getRecords();
        return rows.isEmpty() ? 0L : rows.get(0).getId();
    }

    @Override
    public List<ArchiveMessage> after(String conversationId, long afterMessageId, int limit) {
        if (limit <= 0) {
            return List.of();
        }

        // 严格大于游标 + 正序：保证"每次往前推进一点"，不会重复消费同一条消息
        Page<ChatMessageEntity> page = new Page<>(1, limit, false);
        List<ChatMessageEntity> rows = chatMessageMapper.selectPage(page,
                        Wrappers.<ChatMessageEntity>lambdaQuery()
                                .eq(ChatMessageEntity::getConversationId, conversationId)
                                .gt(ChatMessageEntity::getId, afterMessageId)
                                .orderByAsc(ChatMessageEntity::getId))
                .getRecords();

        List<ArchiveMessage> messages = new ArrayList<>(rows.size());
        for (ChatMessageEntity row : rows) {
            messages.add(new ArchiveMessage(row.getId(),
                    new ChatMessage(MessageRoleCodec.fromStorage(row.getRole()), row.getSenderId(), row.getContent())));
        }
        return List.copyOf(messages);
    }
}
