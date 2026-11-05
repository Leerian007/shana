package com.shana.report;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * 报表周期：自然月。
 *
 * <p>区间一律用"左闭右开"（{@code [start, end)}），而不是"当月最后一天的 23:59:59"。
 * 后者看起来直观，但 {@code timestamptz} 有小数秒，{@code 23:59:59} 会漏掉最后不到一秒的消息；
 * 写成 {@code 23:59:59.999} 也只是把漏洞缩小而非消除。右开区间没有这个问题。
 *
 * <p>时区固定东八区而不是取服务器默认值：报表是给人看的，"11 月"指的就是北京时间 11 月。
 * 服务器若跑在 UTC，按默认时区统计会把每月最后 8 小时算到下个月去。
 */
public record ReportPeriod(YearMonth month) {

    /** 报表统一使用东八区，导出的时间戳与 SQL 的日期分组才对得上。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public ReportPeriod {
        Objects.requireNonNull(month, "month must not be null");
    }

    /** 解析 {@code 2026-11} 这种形式。格式不对直接抛，由接口层转成 400。 */
    public static ReportPeriod parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("period 不能为空，格式为 yyyy-MM");
        }
        try {
            return new ReportPeriod(YearMonth.parse(text.strip()));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("period 格式应为 yyyy-MM，例如 2026-11，实际为: " + text, e);
        }
    }

    /** 上一个月，供定时任务在月初自动跑报表时使用。 */
    public static ReportPeriod previousMonth() {
        return new ReportPeriod(YearMonth.now(ZONE).minusMonths(1));
    }

    /** 存储与查询用的稳定标识，如 {@code 2026-11}。 */
    public String label() {
        return month.toString();
    }

    /** 人读的标题，如 {@code 2026 年 11 月}。 */
    public String displayName() {
        return month.getYear() + " 年 " + month.getMonthValue() + " 月";
    }

    /** 区间起点（含），当月 1 日 00:00:00。 */
    public OffsetDateTime startInclusive() {
        return month.atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
    }

    /** 区间终点（不含），次月 1 日 00:00:00。 */
    public OffsetDateTime endExclusive() {
        return month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toOffsetDateTime();
    }

    /** 当月天数，用来算"沉默天数"。 */
    public int days() {
        return month.lengthOfMonth();
    }

    public LocalDate firstDay() {
        return month.atDay(1);
    }

    public boolean contains(LocalDate date) {
        return YearMonth.from(date).equals(month);
    }
}
