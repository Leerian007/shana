package com.shana.api.vo;

import com.shana.report.MonthlyReport;

/**
 * 报表列表项：只带关键指标，不带 Markdown 正文。
 *
 * <p>一个月的全部群报表如果每个都塞几 KB 的 Markdown，接口响应会很臃肿，
 * 而列表页实际只需要排行和数字。要看全文再调详情接口。
 */
public record MonthlyReportSummaryVO(
        String conversationId,
        String period,
        String periodDisplay,
        int messageCount,
        int activeMembers,
        String topSender,
        int topSenderMessages,
        boolean hasNarrative,
        String narrativeError,
        String generatedAt) {

    public static MonthlyReportSummaryVO from(MonthlyReport report) {
        return new MonthlyReportSummaryVO(
                report.conversationId(),
                report.period().label(),
                report.period().displayName(),
                report.messageCount(),
                report.activeMembers(),
                report.topSender(),
                report.topSenderMessages(),
                report.hasNarrative(),
                report.narrativeError(),
                report.generatedAt() == null ? null : report.generatedAt().toString());
    }
}
