package com.shana.report;

import java.time.LocalDate;
import java.util.Objects;

/** 某一天的消息量。没有消息的日期不会出现在统计结果里，由渲染层补零。 */
public record DailyVolume(LocalDate date, int count) {

    public DailyVolume {
        Objects.requireNonNull(date, "date must not be null");
    }
}
