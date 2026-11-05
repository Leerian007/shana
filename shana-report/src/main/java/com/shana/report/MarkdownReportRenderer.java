package com.shana.report;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把统计结果渲染成 Markdown 报表。
 *
 * <p>刻意做成无状态纯函数：同样的 {@link GroupActivity} 永远产出同样的文本。
 * 报表一旦发出去就会被引用、被截图、被贴进周报，不可复现的报表等于不可信。
 * 唯一的时间来源是传入的 {@code generatedAt}，不在内部调 {@code now()}。
 *
 * <p>排版上的几个决定：
 * <ul>
 *   <li><b>不画 ASCII 折线图</b>，用表格 + 条形字符。折线图在等宽字体下对不齐，
 *       换个渲染器就散架；表格在任何 Markdown 实现里都稳定。</li>
 *   <li><b>零值日期也保留</b>。月报要看的是节奏，"11 号到 15 号一条都没有"
 *       本身就是结论，把空行删掉就看不出这段沉默。</li>
 *   <li><b>话题总结缺失时明确说明原因</b>，而不是悄悄少一段。否则读者会以为
 *       这个群当月确实没聊什么。</li>
 * </ul>
 */
public final class MarkdownReportRenderer {

    /** 排行最多列这么多人。再多就不是"排行"而是成员名单了。 */
    private static final int TOP_MEMBERS = 10;

    /** 条形图最大宽度（字符数）。超过这个宽度在窄屏上会换行，反而看不清。 */
    private static final int BAR_WIDTH = 24;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public String render(GroupActivity activity, String narrative, String narrativeError,
                         java.time.OffsetDateTime generatedAt) {
        StringBuilder sb = new StringBuilder(2048);
        ReportPeriod period = activity.period();

        sb.append("# 群聊月度报告 · ").append(period.displayName()).append("\n\n");
        sb.append("- 群：`").append(activity.conversationId()).append("`\n");
        sb.append("- 周期：").append(period.firstDay()).append(" ~ ")
                .append(period.month().atEndOfMonth()).append("\n");
        sb.append("- 生成时间：").append(generatedAt.format(STAMP)).append("\n\n");

        if (activity.isEmpty()) {
            sb.append("> 本月没有采集到成员发言。\n");
            sb.append(">\n");
            sb.append("> 可能的原因：这段时间群里确实没人说话；或消息未带 senderId，被统计口径排除。\n");
            return sb.toString();
        }

        appendOverview(sb, activity);

        if (narrative != null && !narrative.isBlank()) {
            sb.append("## 本月话题\n\n").append(narrative.strip()).append("\n\n");
        } else {
            sb.append("## 本月话题\n\n");
            sb.append("> 未生成话题总结");
            if (narrativeError != null && !narrativeError.isBlank()) {
                sb.append("：").append(narrativeError);
            }
            sb.append("。以上统计数字不受影响。\n\n");
        }

        appendRanking(sb, activity);
        appendDaily(sb, activity);
        appendHourly(sb, activity);

        return sb.toString();
    }

    private void appendOverview(StringBuilder sb, GroupActivity activity) {
        sb.append("## 概览\n\n");
        sb.append("| 指标 | 数值 |\n");
        sb.append("| --- | --- |\n");
        sb.append("| 成员发言 | ").append(activity.totalMessages()).append(" 条 |\n");
        sb.append("| 活跃成员 | ").append(activity.activeMembers()).append(" 人 |\n");
        sb.append("| 人均发言 | ").append(format1(activity.averageMessagesPerMember())).append(" 条 |\n");
        sb.append("| 平均每条 | ").append(activity.averageCharsPerMessage()).append(" 字 |\n");

        DailyVolume busiest = activity.busiestDay();
        if (busiest != null) {
            sb.append("| 最活跃的一天 | ").append(busiest.date().format(DAY))
                    .append("（").append(busiest.count()).append(" 条） |\n");
        }

        int peak = activity.peakHour();
        if (peak >= 0) {
            sb.append("| 最活跃时段 | ").append(String.format("%02d:00–%02d:00", peak, (peak + 1) % 24))
                    .append(" |\n");
        }

        sb.append("| 无发言天数 | ").append(activity.silentDays()).append(" 天 |\n\n");
    }

    private void appendRanking(StringBuilder sb, GroupActivity activity) {
        List<MemberActivity> members = activity.members();
        sb.append("## 发言排行\n\n");
        sb.append("| # | 成员 | 条数 | 占比 | 平均字数 |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");

        int limit = Math.min(TOP_MEMBERS, members.size());
        for (int i = 0; i < limit; i++) {
            MemberActivity m = members.get(i);
            sb.append("| ").append(i + 1)
                    .append(" | ").append(m.senderId())
                    .append(" | ").append(m.messageCount())
                    .append(" | ").append(percent(m.messageCount(), activity.totalMessages()))
                    .append(" | ").append(m.averageChars())
                    .append(" |\n");
        }
        sb.append('\n');

        if (members.size() > limit) {
            sb.append("> 另有 ").append(members.size() - limit)
                    .append(" 位成员发言较少，未列入排行。\n\n");
        }
    }

    private void appendDaily(StringBuilder sb, GroupActivity activity) {
        // SQL 的 group by 只会返回有消息的日期。这里补全整月，缺的日期记 0——
        // "11 号到 15 号一条都没有"本身就是结论，不能因为查询没返回这几行就消失
        Map<LocalDate, Integer> byDate = activity.daily().stream()
                .collect(Collectors.toMap(DailyVolume::date, DailyVolume::count, Integer::sum));
        ReportPeriod period = activity.period();
        List<DailyVolume> daily = new ArrayList<>(period.days());
        for (LocalDate date = period.firstDay(); !date.isAfter(period.month().atEndOfMonth()); date = date.plusDays(1)) {
            daily.add(new DailyVolume(date, byDate.getOrDefault(date, 0)));
        }

        int max = daily.stream().mapToInt(DailyVolume::count).max().orElse(0);

        sb.append("## 每日消息量\n\n");
        sb.append("| 日期 | 条数 | 分布 |\n");
        sb.append("| --- | --- | --- |\n");
        for (DailyVolume day : daily) {
            sb.append("| ").append(day.date().format(DAY))
                    .append(" | ").append(day.count())
                    .append(" | ").append(bar(day.count(), max))
                    .append(" |\n");
        }
        sb.append('\n');
    }

    private void appendHourly(StringBuilder sb, GroupActivity activity) {
        // 同理补全 24 小时：只有 0 点有消息时，孤零零一行 "00:00"，看不出"其余时段没人说话"
        Map<Integer, Integer> byHour = activity.hourly().stream()
                .collect(Collectors.toMap(HourlyVolume::hour, HourlyVolume::count, Integer::sum));
        List<HourlyVolume> hourly = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            hourly.add(new HourlyVolume(hour, byHour.getOrDefault(hour, 0)));
        }

        int max = hourly.stream().mapToInt(HourlyVolume::count).max().orElse(0);

        sb.append("## 时段分布\n\n");
        sb.append("| 时段 | 条数 | 分布 |\n");
        sb.append("| --- | --- | --- |\n");
        for (HourlyVolume hour : hourly) {
            sb.append("| ").append(String.format("%02d:00", hour.hour()))
                    .append(" | ").append(hour.count())
                    .append(" | ").append(bar(hour.count(), max))
                    .append(" |\n");
        }
        sb.append('\n');
    }

    /** 相对最大值画条形，至少 1 格，否则"有 1 条"和"0 条"在视觉上没区别。 */
    private static String bar(int value, int max) {
        if (value <= 0 || max <= 0) {
            return "";
        }
        int length = Math.max(1, (int) Math.round((double) value / max * BAR_WIDTH));
        return "█".repeat(length);
    }

    private static String percent(int part, int total) {
        return total == 0 ? "0%" : format1(part * 100.0 / total) + "%";
    }

    private static String format1(double value) {
        return String.valueOf(Math.round(value * 10) / 10.0);
    }
}
