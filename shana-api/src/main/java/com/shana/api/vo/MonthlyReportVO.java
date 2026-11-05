package com.shana.api.vo;

import com.shana.report.MonthlyReport;

/** 报表详情：在摘要基础上带话题总结与 Markdown 全文。 */
public record MonthlyReportVO(
        String conversationId,
        String period,
        String periodDisplay,
        int messageCount,
        int activeMembers,
        String topSender,
        int topSenderMessages,
        String narrative,
        String narrativeError,
        String markdown,
        String generatedAt) {

    public static MonthlyReportVO from(MonthlyReport report) {
        return new MonthlyReportVO(
                report.conversationId(),
                report.period().label(),
                report.period().displayName(),
                report.messageCount(),
                report.activeMembers(),
                report.topSender(),
                report.topSenderMessages(),
                report.narrative(),
                report.narrativeError(),
                report.markdown(),
                report.generatedAt() == null ? null : report.generatedAt().toString());
    }
}
