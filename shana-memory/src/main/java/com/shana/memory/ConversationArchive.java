package com.shana.memory;

import com.shana.model.ChatMessage;

import java.util.List;

/**
 * 消息归档端口：按消息 ID 顺序读取未被摘要消化的历史消息。
 *
 * <p>为什么单独一个接口而不塞进 {@link ConversationMemory}：
 * 后者是"最近 N 条的窗口"，语义是给模型接话用的；摘要需要的是"从游标往后的一批原文"，
 * 是顺序扫描。两种访问模式的实现天差地别（窗口可以放 Redis，扫描只能落在持久化存储上），
 * 混在一个接口里会逼着每个实现都去实现自己根本用不上的方法。
 *
 * <p>只有持久化存储（PostgreSQL）能提供本端口。用 Redis 或内存做上下文时，
 * 历史消息不完整，长期记忆不成立——这是刻意的约束，不是遗漏。
 */
public interface ConversationArchive {

    /** 会话中最大的消息 ID；会话不存在或为空时返回 0。 */
    long latestMessageId(String conversationId);

    /**
     * 读取 ID 严格大于 {@code afterMessageId} 的最早一批消息，按 ID 正序。
     *
     * @param limit 单批上限，避免一次把几万条消息读进内存
     */
    List<ArchiveMessage> after(String conversationId, long afterMessageId, int limit);
}
