package com.shana.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.shana.infrastructure.persistence.entity.GroupMonthlyReportEntity;
import com.shana.infrastructure.persistence.mapper.GroupMonthlyReportMapper;
import com.shana.report.MonthlyReport;
import com.shana.report.ReportPeriod;
import com.shana.report.ReportRepository;

import java.util.List;
import java.util.Optional;

/** 基于 group_monthly_report 的报表存储。 */
public class JdbcReportRepository implements ReportRepository {

    private final GroupMonthlyReportMapper mapper;

    public JdbcReportRepository(GroupMonthlyReportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(MonthlyReport report) {
        mapper.upsert(toEntity(report));
    }

    @Override
    public Optional<MonthlyReport> find(String conversationId, ReportPeriod period) {
        QueryWrapper<GroupMonthlyReportEntity> query = new QueryWrapper<GroupMonthlyReportEntity>()
                .eq("conversation_id", conversationId)
                .eq("period", period.label());
        return Optional.ofNullable(mapper.selectOne(query)).map(this::toReport);
    }

    @Override
    public List<MonthlyReport> findAll(ReportPeriod period) {
        QueryWrapper<GroupMonthlyReportEntity> query = new QueryWrapper<GroupMonthlyReportEntity>()
                .eq("period", period.label())
                // 和索引 (period, message_count DESC) 的顺序一致，省掉内存排序
                .orderByDesc("message_count");
        return mapper.selectList(query).stream().map(this::toReport).toList();
    }

    private GroupMonthlyReportEntity toEntity(MonthlyReport report) {
        GroupMonthlyReportEntity entity = new GroupMonthlyReportEntity();
        entity.setConversationId(report.conversationId());
        entity.setPeriod(report.period().label());
        entity.setMessageCount(report.messageCount());
        entity.setActiveMembers(report.activeMembers());
        entity.setTopSender(report.topSender());
        entity.setTopSenderMessages(report.topSenderMessages());
        entity.setNarrative(report.narrative());
        entity.setNarrativeError(report.narrativeError());
        entity.setMarkdown(report.markdown());
        entity.setGeneratedAt(report.generatedAt());
        return entity;
    }

    private MonthlyReport toReport(GroupMonthlyReportEntity entity) {
        return new MonthlyReport(
                entity.getConversationId(),
                ReportPeriod.parse(entity.getPeriod()),
                entity.getMessageCount(),
                entity.getActiveMembers(),
                entity.getTopSender(),
                entity.getTopSenderMessages() == null ? 0 : entity.getTopSenderMessages(),
                entity.getNarrative(),
                entity.getNarrativeError(),
                entity.getMarkdown(),
                entity.getGeneratedAt());
    }
}
