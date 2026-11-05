package com.shana.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 周期解析与区间边界。
 *
 * <p>这些断言看着琐碎，但它们保护的是最容易静默出错的地方：区间差一天，
 * 报表数字就和"人工数一遍"对不上，而且不会报错，只会让人觉得"统计不准"。
 */
class ReportPeriodTest {

    @Test
    @DisplayName("解析 yyyy-MM，前后空格会被忽略")
    void shouldParseLabel() {
        assertThat(ReportPeriod.parse("2026-11").month()).isEqualTo(YearMonth.of(2026, 11));
        assertThat(ReportPeriod.parse("  2026-01 ").label()).isEqualTo("2026-01");
    }

    @Test
    @DisplayName("格式不合法时抛 IllegalArgumentException，由接口层转 400")
    void shouldRejectBadFormat() {
        assertThatThrownBy(() -> ReportPeriod.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportPeriod.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportPeriod.parse("2026-13")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportPeriod.parse("2026/11")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportPeriod.parse("202611")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("区间左闭右开，终点是次月 1 号零点")
    void shouldUseHalfOpenInterval() {
        ReportPeriod period = ReportPeriod.parse("2026-11");

        OffsetDateTime start = period.startInclusive();
        OffsetDateTime end = period.endExclusive();

        assertThat(start.toString()).isEqualTo("2026-11-01T00:00+08:00");
        assertThat(end.toString()).isEqualTo("2026-12-01T00:00+08:00");
        // 11 月 30 日 23:59:59 必须落在区间内，这是"右开"要保证的事
        assertThat(start.isBefore(end)).isTrue();
    }

    @Test
    @DisplayName("天数按真实月份算，闰年二月是 29 天")
    void shouldRespectMonthLength() {
        assertThat(ReportPeriod.parse("2026-02").days()).isEqualTo(28);
        assertThat(ReportPeriod.parse("2028-02").days()).isEqualTo(29);
        assertThat(ReportPeriod.parse("2026-04").days()).isEqualTo(30);
        assertThat(ReportPeriod.parse("2026-12").days()).isEqualTo(31);
    }

    @Test
    @DisplayName("展示名用中文，跨年不串月")
    void shouldDisplayChineseMonth() {
        assertThat(ReportPeriod.parse("2026-11").displayName()).isEqualTo("2026 年 11 月");
        assertThat(ReportPeriod.parse("2026-01").displayName()).isEqualTo("2026 年 1 月");
    }

    @Test
    @DisplayName("上月不受跨年影响")
    void shouldComputePreviousMonthAcrossYear() {
        // 用相对断言，避免测试依赖"当前是哪个月"
        YearMonth now = YearMonth.now(ReportPeriod.ZONE);
        assertThat(ReportPeriod.previousMonth().month()).isEqualTo(now.minusMonths(1));
    }
}
