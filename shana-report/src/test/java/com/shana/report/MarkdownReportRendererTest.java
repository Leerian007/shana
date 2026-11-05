package com.shana.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渲染器测试。
 *
 * <p>重点在两处：<b>补零</b>和<b>降级提示</b>。
 * 前者决定月报能不能看出节奏，后者决定"没有话题总结"会不会被误读成"没聊什么"。
 */
class MarkdownReportRendererTest {

    private final MarkdownReportRenderer renderer = new MarkdownReportRenderer();

    private final OffsetDateTime generatedAt = OffsetDateTime.parse("2026-12-01T02:30:00+08:00");

    @Test
    @DisplayName("整月无发言时给出明确提示，不输出空表格")
    void shouldExplainEmptyMonth() {
        GroupActivity empty = new GroupActivity("g1", ReportPeriod.parse("2026-11"),
                0, 0, List.of(), List.of(), List.of(), List.of());

        String md = renderer.render(empty, "", null, generatedAt);

        assertThat(md).contains("本月没有采集到成员发言");
        assertThat(md).doesNotContain("## 发言排行");
        assertThat(md).doesNotContain("| 日期 |");
    }

    @Test
    @DisplayName("正常报表包含概览、排行、每日、时段四个章节")
    void shouldRenderAllSections() {
        String md = renderer.render(activity(), "本月主要在讨论发布节奏。", null, generatedAt);

        assertThat(md).contains("# 群聊月度报告 · 2026 年 11 月");
        assertThat(md).contains("## 概览");
        assertThat(md).contains("## 本月话题");
        assertThat(md).contains("本月主要在讨论发布节奏。");
        assertThat(md).contains("## 发言排行");
        assertThat(md).contains("## 每日消息量");
        assertThat(md).contains("## 时段分布");
    }

    @Test
    @DisplayName("每日与时段都补零：只给了 2 天数据，也要输出整月 30 天和 24 小时")
    void shouldFillMissingDaysAndHours() {
        String md = renderer.render(activity(), "", null, generatedAt);

        // 2026-11 有 30 天
        long dayRows = md.lines().filter(line -> line.matches("\\| 11-\\d{2} \\|.*")).count();
        assertThat(dayRows).isEqualTo(30);

        // 24 个小时
        long hourRows = md.lines().filter(line -> line.matches("\\| \\d{2}:00 \\|.*")).count();
        assertThat(hourRows).isEqualTo(24);

        // 没有消息的日期显示 0，而不是被省略
        assertThat(md).contains("| 11-05 | 0 |");
    }

    @Test
    @DisplayName("话题总结缺失时写明原因，并声明统计数字不受影响")
    void shouldExplainMissingNarrative() {
        String md = renderer.render(activity(), "", "调用模型失败：timeout", generatedAt);

        assertThat(md).contains("未生成话题总结");
        assertThat(md).contains("调用模型失败：timeout");
        assertThat(md).contains("统计数字不受影响");
    }

    @Test
    @DisplayName("排行只列前 10，其余用一句说明带过")
    void shouldCapRankingAtTen() {
        List<MemberActivity> many = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            many.add(new MemberActivity("u_" + i, 20 - i, (20 - i) * 10L,
                    OffsetDateTime.parse("2026-11-02T10:00:00+08:00"),
                    OffsetDateTime.parse("2026-11-03T10:00:00+08:00")));
        }
        GroupActivity activity = new GroupActivity("g1", ReportPeriod.parse("2026-11"),
                120, 1200, many,
                List.of(new DailyVolume(LocalDate.of(2026, 11, 2), 120)),
                List.of(new HourlyVolume(10, 120)),
                List.of());

        String md = renderer.render(activity, "", null, generatedAt);

        assertThat(md).contains("| u_10 |");
        assertThat(md).doesNotContain("| u_11 |");
        assertThat(md).contains("另有 5 位成员发言较少");
    }

    @Test
    @DisplayName("同样的输入产出同样的文本（报表必须可复现）")
    void shouldBeDeterministic() {
        GroupActivity activity = activity();
        String first = renderer.render(activity, "话题 A", null, generatedAt);
        String second = renderer.render(activity, "话题 A", null, generatedAt);

        assertThat(first).isEqualTo(second);
    }

    private GroupActivity activity() {
        List<MemberActivity> members = List.of(
                new MemberActivity("u_zhao", 60, 900,
                        OffsetDateTime.parse("2026-11-02T09:12:00+08:00"),
                        OffsetDateTime.parse("2026-11-28T22:40:00+08:00")),
                new MemberActivity("u_li", 40, 600,
                        OffsetDateTime.parse("2026-11-03T14:00:00+08:00"),
                        OffsetDateTime.parse("2026-11-29T20:00:00+08:00")));

        return new GroupActivity("g1", ReportPeriod.parse("2026-11"),
                100, 1500, members,
                List.of(new DailyVolume(LocalDate.of(2026, 11, 2), 70),
                        new DailyVolume(LocalDate.of(2026, 11, 3), 30)),
                List.of(new HourlyVolume(9, 40), new HourlyVolume(22, 60)),
                List.of());
    }

    @Test
    @DisplayName("跨年月份的天数正确（2 月的月报不能有 31 行）")
    void shouldHandleShortMonth() {
        GroupActivity activity = new GroupActivity("g1", new ReportPeriod(YearMonth.of(2026, 2)),
                10, 100,
                List.of(new MemberActivity("u_a", 10, 100,
                        OffsetDateTime.parse("2026-02-01T10:00:00+08:00"),
                        OffsetDateTime.parse("2026-02-10T10:00:00+08:00"))),
                List.of(new DailyVolume(LocalDate.of(2026, 2, 1), 10)),
                List.of(new HourlyVolume(10, 10)),
                List.of());

        String md = renderer.render(activity, "", null, generatedAt);

        long dayRows = md.lines().filter(line -> line.matches("\\| 02-\\d{2} \\|.*")).count();
        assertThat(dayRows).isEqualTo(28);
    }
}
