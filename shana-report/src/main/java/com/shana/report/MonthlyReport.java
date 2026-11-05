package com.shana.report;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 一份已生成的月度报表。
 *
 * <p>它是一个<b>快照</b>：生成当时是什么数就是什么数，之后群里的新消息不会改变它。
 * 这是有意的——月报一旦发出就应该是可复现的，否则同一份"11 月报表"今天看和下周看不一样，
 * 没法对账也没法归档。需要最新数据就重新生成一份覆盖掉。
 *
 * <p>不携带 {@link GroupActivity}：明细（成员排行、每日曲线）只在生成过程中用一次，
 * 存下来既占空间又容易和汇总字段不一致。markdown 已经把该展示的都固化了。
 *
 * @param narrative      模型归纳的话题总结；未启用模型或生成失败时为空串
 * @param narrativeError 总结失败原因，成功后为 null。留在报表里而不是只写日志，
 *                       是因为"报表没有话题总结"这件事本身需要被看见，否则会被当成 bug
 */
public record MonthlyReport(
        String conversationId,
        ReportPeriod period,
        int messageCount,
        int activeMembers,
        String topSender,
        int topSenderMessages,
        String narrative,
        String narrativeError,
        String markdown,
        OffsetDateTime generatedAt) {

    public MonthlyReport {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(period, "period must not be null");
        Objects.requireNonNull(markdown, "markdown must not be null");
        narrative = narrative == null ? "" : narrative;
    }

    /** 从统计结果组装报表。 */
    public static MonthlyReport from(GroupActivity activity,
                                     String narrative,
                                     String narrativeError,
                                     String markdown,
                                     OffsetDateTime generatedAt) {
        MemberActivity top = activity.topSender();
        return new MonthlyReport(
                activity.conversationId(),
                activity.period(),
                activity.totalMessages(),
                activity.activeMembers(),
                top == null ? null : top.senderId(),
                top == null ? 0 : top.messageCount(),
                narrative,
                narrativeError,
                markdown,
                generatedAt);
    }

    public boolean hasNarrative() {
        return !narrative.isBlank();
    }
}
