package com.shana.infrastructure.persistence.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 报表聚合查询的中间结果。
 *
 * <p>集中放在一个文件里，是因为它们只在 {@code GroupReportMapper} 和
 * {@code JdbcGroupActivitySource} 之间流转，一屏就能看全；拆成四个文件反而要来回翻。
 *
 * <p>用 POJO 而不是 record 接结果：MyBatis 的默认映射靠 setter，
 * record 需要额外的构造器映射配置，为了四个只活一瞬的中间结构去改全局配置不划算。
 */
public final class ReportAggregates {

    private ReportAggregates() {
    }

    /** 按成员聚合。 */
    @Data
    public static class MemberRow {
        private String senderId;
        private Integer messageCount;
        /** char_length 之和；PG 里中文算 1 个字符，符合"字数"的直觉 */
        private Long charCount;
        private OffsetDateTime firstAt;
        private OffsetDateTime lastAt;
    }

    /** 按天聚合，day 已按东八区切好。 */
    @Data
    public static class DailyRow {
        private LocalDate day;
        private Integer messageCount;
    }

    /** 按小时聚合，hourOfDay 取值 0~23（东八区）。 */
    @Data
    public static class HourlyRow {
        private Integer hourOfDay;
        private Integer messageCount;
    }

    /** 抽样消息，用于话题归纳。 */
    @Data
    public static class SampleRow {
        private String senderId;
        private String content;
        private OffsetDateTime createdAt;
    }
}
