package com.shana.memory;

import com.shana.model.ChatMessage;

import java.util.List;

/**
 * 群聊会话上下文。
 * <p>以 {@code conversationId}（群聊 ID）为维度保存最近的对话消息，供模型调用时组装 prompt。
 * <p>窗口语义：{@link #history(String)} 只返回最近 {@code shana.memory.max-messages} 条。
 * 是否真的丢弃更早的消息由实现决定——内存实现会淘汰，数据库实现只截断读取、保留全量历史。
 * <p>后续的长期记忆（对话摘要、向量检索）可以新增独立接口，而不是把职责塞进本接口。
 */
public interface ConversationMemory {

    /** 追加一条消息，超出窗口时淘汰最早的消息。 */
    void append(String conversationId, ChatMessage message);

    /** 读取窗口内的历史消息，按时间正序返回只读列表。 */
    List<ChatMessage> history(String conversationId);

    /** 清空指定会话的上下文。 */
    void clear(String conversationId);
}
