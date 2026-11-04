package com.shana.memory;

import java.util.List;

/**
 * 长期记忆端口：滚动摘要 + 关键信息。
 *
 * <p>与 {@link ConversationMemory} 的分工：
 * 后者是"原始对话的最近 N 条"，每条消息都在，用于逐字接上话；
 * 本接口是"压缩后的历史"，用于跨天、跨窗口地记住群里发生过什么。
 * 两者不能互相替代——摘要会丢细节，原文会超出上下文窗口。
 *
 * <p>只定义读写两类操作，不涉及"什么时候该摘要"：那是编排层（core）的判断，
 * 换存储实现时不应该被迫重新想一遍触发策略。
 */
public interface LongTermMemory {

    /** 读取当前记忆快照；没有记忆时返回 {@link MemorySnapshot#empty()}，不返回 null。 */
    MemorySnapshot load(String conversationId, int maxFacts);

    /**
     * 推进摘要。仅当库里的 {@code covered_message_id} 仍等于 {@code expectedCoveredMessageId} 时生效。
     *
     * <p>这是并发保护：两个实例同时给同一个群做摘要时，只有一个能推进游标，
     * 另一个的结果被丢弃，避免摘要被"后写完的旧内容"覆盖。
     *
     * @return 是否写入成功；false 表示别人已经先推进过，调用方应丢弃本次抽取的事实
     */
    boolean advanceSummary(String conversationId, String summary, long coveredMessageId, long expectedCoveredMessageId);

    /**
     * 追加关键信息。重复的条目（同会话、同类别、同主体、同内容）会被忽略。
     *
     * @param sourceMessageId 这批信息来自的最大消息 ID，用于回溯
     */
    void addFacts(String conversationId, List<MemoryFact> facts, long sourceMessageId);
}
