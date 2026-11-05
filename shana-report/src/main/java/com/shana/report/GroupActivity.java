package com.shana.report;

import java.util.List;
import java.util.Objects;

/**
 * 一个群在一个月内的全部统计结果，是报表的唯一数据来源。
 *
 * <p>统计口径：只算 <b>成员发言</b>（{@code role = 'user'} 且 {@code sender_id} 非空）。
 * 助手自己的回复不计入——它不是群里的"人"，算进去会让"人均发言"这类指标失真，
 * 而且助手说话越多（群越活跃）反而越显得人均更高，逻辑上是反的。
 * 未带 senderId 的历史消息同样排除，否则会凭空多出一个匿名成员。
 *
 * <p>本对象只放"算出来的东西"，不放"怎么展示"。条形图长度、Top N 截断、
 * 百分比保留几位小数都在渲染层决定，换报表格式时不用重新计算统计。
 */
public record GroupActivity(
        String conversationId,
        ReportPeriod period,
        int totalMessages,
        long totalChars,
        List<MemberActivity> members,
        List<DailyVolume> daily,
        List<HourlyVolume> hourly,
        List<SampleMessage> samples) {

    public GroupActivity {
        Objects.requireNonNull(conversationId, "conversationId must not be null");
        Objects.requireNonNull(period, "period must not be null");
        members = members == null ? List.of() : List.copyOf(members);
        daily = daily == null ? List.of() : List.copyOf(daily);
        hourly = hourly == null ? List.of() : List.copyOf(hourly);
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    /** 本月有过发言的成员数。 */
    public int activeMembers() {
        return members.size();
    }

    /** 人均发言条数，保留一位小数。 */
    public double averageMessagesPerMember() {
        return members.isEmpty() ? 0 : round1((double) totalMessages / members.size());
    }

    /** 平均每条消息多少个字，反映"是短句闲聊还是长段讨论"。 */
    public int averageCharsPerMessage() {
        return totalMessages == 0 ? 0 : (int) (totalChars / totalMessages);
    }

    /** 消息最多的一天；整月无消息时返回 null。 */
    public DailyVolume busiestDay() {
        return daily.stream().max((a, b) -> Integer.compare(a.count(), b.count())).orElse(null);
    }

    /** 最活跃的小时；整月无消息时返回 -1。 */
    public int peakHour() {
        return hourly.stream().max((a, b) -> Integer.compare(a.count(), b.count()))
                .map(HourlyVolume::hour)
                .orElse(-1);
    }

    /** 整月一条消息都没有的日期数，用来体现"这个群有多冷"。 */
    public int silentDays() {
        return Math.max(0, period.days() - daily.size());
    }

    /** 发言最多的人；无人发言时返回 null。 */
    public MemberActivity topSender() {
        return members.isEmpty() ? null : members.getFirst();
    }

    public boolean isEmpty() {
        return totalMessages == 0;
    }

    private static double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
