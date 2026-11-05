package com.shana.report;

import java.util.List;

/**
 * 报表数据源端口：从聊天历史里算出统计结果。
 *
 * <p>放在 report 模块而不是直接写 SQL，是为了让编排和渲染能在没有数据库的情况下测试。
 * 具体实现（JdbcGroupActivitySource）在 infrastructure，那里才认识表和字段。
 *
 * <p>只提供"读"，没有任何写入方法：分析是只读操作，不该有能力改动原始消息。
 */
public interface GroupActivitySource {

    /**
     * 统计某个群在某个周期内的活跃情况。
     *
     * <p>约定：群里一条成员发言都没有时，返回一个 {@code totalMessages = 0} 的空结果，
     * <b>不返回 null，也不抛异常</b>。让调用方判断"要不要出报表"，比让它接 null 好。
     */
    GroupActivity load(String conversationId, ReportPeriod period);

    /**
     * 列出该周期内有成员发言的群。
     *
     * <p>批量任务用它决定跑哪些群。刻意不是"所有出现过的群"：
     * 一个已经有半年没人说话的群，给它生成一份全零的月报没有价值，
     * 还会让定时任务随着历史群数量增长而越来越慢。
     */
    List<String> conversationsActiveIn(ReportPeriod period);
}
