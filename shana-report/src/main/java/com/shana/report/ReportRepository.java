package com.shana.report;

import java.util.List;
import java.util.Optional;

/**
 * 报表存储端口。
 *
 * <p>{@link #save} 是覆写语义而不是插入：同一个群同一个月只应该有一份报表。
 * 重跑（改了口径、补了数据、上次模型总结失败）应该是替换而不是叠加，
 * 否则查询接口要面对"四份 2026-11 报表该返回哪份"的问题。
 */
public interface ReportRepository {

    /** 保存或覆盖同一 (conversationId, period) 的报表。 */
    void save(MonthlyReport report);

    Optional<MonthlyReport> find(String conversationId, ReportPeriod period);

    /** 某周期下所有群的报表，按发言量降序。 */
    List<MonthlyReport> findAll(ReportPeriod period);
}
