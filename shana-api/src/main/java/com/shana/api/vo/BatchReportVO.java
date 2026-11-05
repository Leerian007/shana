package com.shana.api.vo;

import com.shana.report.MonthlyReportService;

import java.util.List;

/**
 * 批量生成的结果。
 *
 * <p>把失败清单单独列出来，而不是只返回"成功 N 个"：一个月报任务跑了 20 个群，
 * 如果第 3 个群因为模型超时失败，运维需要知道是哪一个，而不是只知道"有 1 个失败"。
 */
public record BatchReportVO(
        String period,
        int total,
        int succeeded,
        int failed,
        List<FailedConversation> failures) {

    public static BatchReportVO from(MonthlyReportService.BatchResult result) {
        return new BatchReportVO(
                result.period().label(),
                result.total(),
                result.succeeded().size(),
                result.failed().size(),
                result.failed().stream()
                        .map(f -> new FailedConversation(f.conversationId(), f.reason()))
                        .toList());
    }

    public record FailedConversation(String conversationId, String reason) {
    }
}
