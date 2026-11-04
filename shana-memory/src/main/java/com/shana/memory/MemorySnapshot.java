package com.shana.memory;

import java.util.List;

/**
 * 某个会话此刻的长期记忆快照：一版滚动摘要 + 若干关键信息。
 *
 * @param summary          当前摘要；从未生成过时为 null
 * @param coveredMessageId 摘要已消化到的最大消息 ID；从未生成过时为 0
 * @param facts            已抽取的关键信息，按新旧倒序
 */
public record MemorySnapshot(String summary, long coveredMessageId, List<MemoryFact> facts) {

    public MemorySnapshot {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    public static MemorySnapshot empty() {
        return new MemorySnapshot(null, 0L, List.of());
    }

    /** 没有任何可用的记忆内容——调用方据此决定"不要把空段落拼进 prompt"。 */
    public boolean isEmpty() {
        return (summary == null || summary.isBlank()) && facts.isEmpty();
    }
}
