package com.shana.core.memory;

import com.shana.memory.MemoryFact;

import java.util.List;

/**
 * 模型一次摘要调用的产出：更新后的摘要 + 新抽取的关键信息。
 *
 * @param summary 滚动摘要全文（不是增量片段）
 * @param facts   本轮新增的事实条目，可能为空
 */
public record SummaryDraft(String summary, List<MemoryFact> facts) {

    public SummaryDraft {
        facts = facts == null ? List.of() : List.copyOf(facts);
    }
}
