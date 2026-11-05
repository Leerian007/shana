package com.shana.report;

/**
 * 报表归档端口：把 Markdown 落成文件。
 *
 * <p>和 {@link ReportRepository} 是两种用途，不能互相替代：
 * 数据库那份供接口查询、按周期检索；文件这份供人直接翻看、进版本库、贴进周报。
 * 落盘失败不应该让整次生成失败——报表已经在库里了，用户重新触发一次即可。
 *
 * @return 归档后的文件路径；不归档（比如关闭了落盘）时返回 null
 */
public interface ReportArchive {

    String write(MonthlyReport report);
}
