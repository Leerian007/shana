package com.shana.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shana.infrastructure.persistence.entity.ConversationSummaryEntity;
import com.shana.infrastructure.persistence.entity.MemoryFactEntity;
import com.shana.infrastructure.persistence.mapper.ConversationSummaryMapper;
import com.shana.infrastructure.persistence.mapper.MemoryFactMapper;
import com.shana.memory.FactKind;
import com.shana.memory.LongTermMemory;
import com.shana.memory.MemoryFact;
import com.shana.memory.MemorySnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 PostgreSQL 的长期记忆实现。
 *
 * <p>为什么跟着 {@code store=jdbc} 一起启用：长期记忆依赖完整的历史消息，
 * 只有持久化存储才有。Redis 版上下文只留窗口内的几十条，用它做摘要会得出
 * "群里只聊过这 20 句"的错觉，比没有记忆更糟。
 * 所以这里刻意不提供"退化实现"，条件不满足时整个端口不存在，
 * 由编排层跳过记忆装配。
 */
@Component
@ConditionalOnProperty(prefix = "shana.memory", name = "store", havingValue = "jdbc", matchIfMissing = true)
public class JdbcLongTermMemory implements LongTermMemory {

    private final ConversationSummaryMapper summaryMapper;
    private final MemoryFactMapper factMapper;

    public JdbcLongTermMemory(ConversationSummaryMapper summaryMapper, MemoryFactMapper factMapper) {
        this.summaryMapper = summaryMapper;
        this.factMapper = factMapper;
    }

    @Override
    public MemorySnapshot load(String conversationId, int maxFacts) {
        ConversationSummaryEntity summary = summaryMapper.selectById(conversationId);
        // maxFacts <= 0 表示"只要摘要和游标"，摘要流程用得到，没必要多查一次事实表
        List<MemoryFact> facts = maxFacts > 0 ? loadFacts(conversationId, maxFacts) : List.of();

        return new MemorySnapshot(
                summary == null ? null : summary.getSummary(),
                // 字段在库里是 NOT NULL，这里仍按可空处理：手写 SQL 或迁移异常时不要炸在拆箱上
                summary == null || summary.getCoveredMessageId() == null ? 0L : summary.getCoveredMessageId(),
                facts);
    }

    @Override
    public boolean advanceSummary(String conversationId, String summary, long coveredMessageId, long expectedCoveredMessageId) {
        return summaryMapper.upsertIfNotAdvanced(conversationId, summary, coveredMessageId, expectedCoveredMessageId) > 0;
    }

    @Override
    public void addFacts(String conversationId, List<MemoryFact> facts, long sourceMessageId) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        for (MemoryFact fact : facts) {
            // 重复条目由唯一约束挡掉，这里不做"先查再插"，省一次往返也避免并发写重
            factMapper.insertIgnoreDuplicate(
                    conversationId,
                    fact.kind().name(),
                    fact.subject(),
                    fact.content(),
                    sourceMessageId);
        }
    }

    private List<MemoryFact> loadFacts(String conversationId, int maxFacts) {
        // 与上下文实现一样关掉 count：这里只关心"最新几条"，总数没有意义
        Page<MemoryFactEntity> page = new Page<>(1, maxFacts, false);
        List<MemoryFactEntity> rows = factMapper.selectPage(page,
                        Wrappers.<MemoryFactEntity>lambdaQuery()
                                .eq(MemoryFactEntity::getConversationId, conversationId)
                                .orderByDesc(MemoryFactEntity::getId))
                .getRecords();

        List<MemoryFact> facts = new ArrayList<>(rows.size());
        for (MemoryFactEntity row : rows) {
            facts.add(MemoryFact.of(parseKind(row.getKind()), row.getSubject(), row.getContent()));
        }
        return List.copyOf(facts);
    }

    private FactKind parseKind(String kind) {
        try {
            return FactKind.valueOf(kind);
        } catch (IllegalArgumentException | NullPointerException e) {
            // 类别是抽取时自己写的枚举名，出现未知值说明数据被外部改过。
            // 降级成 FACT 而不是直接失败：一条类别不明的事实仍然有信息价值，
            // 不值得让整段记忆读取失败。
            return FactKind.FACT;
        }
    }
}
