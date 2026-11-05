package com.shana.infrastructure.persistence;

import com.shana.infrastructure.persistence.dto.ReportAggregates;
import com.shana.infrastructure.persistence.mapper.GroupReportMapper;
import com.shana.report.DailyVolume;
import com.shana.report.GroupActivity;
import com.shana.report.GroupActivitySource;
import com.shana.report.HourlyVolume;
import com.shana.report.MemberActivity;
import com.shana.report.ReportPeriod;
import com.shana.report.SampleMessage;

import java.util.List;

/**
 * 基于 chat_message 的报表数据源。
 *
 * <p>一个月一个群要跑四次查询（成员 / 每日 / 每小时 / 抽样）。看起来可以合并成一条
 * 大 SQL 用多个 CTE，但没有必要：四次都是同一区间上的索引扫描，单次都在毫秒级，
 * 而拆开后每条语句都能单独拿出来 explain，出问题好定位。
 *
 * <p>总消息数由成员行加总得出，而不是再跑一次 {@code count(*)}。
 * 这样"总条数"和"排行里各人之和"必然一致——另查一次 count 的话，
 * 两次查询之间若有新消息写入，报表里的数字就会自相矛盾。
 */
public class JdbcGroupActivitySource implements GroupActivitySource {

    private final GroupReportMapper mapper;
    private final int maxSamples;

    public JdbcGroupActivitySource(GroupReportMapper mapper, int maxSamples) {
        if (maxSamples <= 0) {
            throw new IllegalArgumentException("shana.report.narrative.max-samples 必须大于 0");
        }
        this.mapper = mapper;
        this.maxSamples = maxSamples;
    }

    @Override
    public GroupActivity load(String conversationId, ReportPeriod period) {
        var from = period.startInclusive();
        var to = period.endExclusive();

        List<MemberActivity> members = mapper.aggregateByMember(conversationId, from, to).stream()
                .map(row -> new MemberActivity(
                        row.getSenderId(),
                        row.getMessageCount() == null ? 0 : row.getMessageCount(),
                        row.getCharCount() == null ? 0L : row.getCharCount(),
                        row.getFirstAt(),
                        row.getLastAt()))
                .toList();

        int totalMessages = members.stream().mapToInt(MemberActivity::messageCount).sum();
        long totalChars = members.stream().mapToLong(MemberActivity::charCount).sum();

        List<DailyVolume> daily = mapper.aggregateByDay(conversationId, from, to).stream()
                .map(row -> new DailyVolume(row.getDay(), row.getMessageCount()))
                .toList();

        List<HourlyVolume> hourly = mapper.aggregateByHour(conversationId, from, to).stream()
                .map(row -> new HourlyVolume(row.getHourOfDay(), row.getMessageCount()))
                .toList();

        // 没有消息时不必再查一次样本：空结果下这条 SQL 也要扫一遍表
        List<SampleMessage> samples = totalMessages == 0
                ? List.of()
                : mapper.sampleMessages(conversationId, from, to, maxSamples).stream()
                        .map(row -> new SampleMessage(row.getSenderId(), row.getContent(), row.getCreatedAt()))
                        .toList();

        return new GroupActivity(conversationId, period, totalMessages, totalChars,
                members, daily, hourly, samples);
    }

    @Override
    public List<String> conversationsActiveIn(ReportPeriod period) {
        return mapper.conversationsActiveIn(period.startInclusive(), period.endExclusive());
    }
}
