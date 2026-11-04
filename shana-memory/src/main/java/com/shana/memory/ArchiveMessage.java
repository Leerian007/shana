package com.shana.memory;

import com.shana.model.ChatMessage;

/**
 * 归档里的一条消息：原始内容 + 数据库主键。
 *
 * @param id      自增主键，摘要游标就钉在它上面
 * @param message 消息本体（含 senderId，摘要时用来标注"谁说的"）
 */
public record ArchiveMessage(long id, ChatMessage message) {
}
