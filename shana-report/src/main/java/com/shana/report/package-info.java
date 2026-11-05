/**
 * 群聊分析：把聊天历史按月聚合成可读报表。
 *
 * <p>结构上分三层，边界比"能跑通"更重要：
 * <ul>
 *   <li><b>统计</b>：{@link com.shana.report.GroupActivitySource} 从历史里算出
 *       {@link com.shana.report.GroupActivity}，纯数字，不含任何展示逻辑；</li>
 *   <li><b>编排</b>：{@link com.shana.report.MonthlyReportService} 串起取数、话题总结、
 *       渲染、入库、归档，并决定哪一步失败可以降级；</li>
 *   <li><b>渲染</b>：{@link com.shana.report.MarkdownReportRenderer} 是纯函数，
 *       换输出格式只需换这一层。</li>
 * </ul>
 *
 * <p>本模块不依赖 core，也不被 core 依赖：报表是旁路能力，不该和对话链路互相牵制。
 */
package com.shana.report;
