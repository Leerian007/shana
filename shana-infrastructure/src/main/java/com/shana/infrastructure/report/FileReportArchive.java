package com.shana.infrastructure.report;

import com.shana.report.MonthlyReport;
import com.shana.report.ReportArchive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 把报表写成 Markdown 文件。
 *
 * <p>目录结构 {@code <base>/<yyyy-MM>/<conversationId>.md}，按月份分目录：
 * 一个群跑满一年就是 12 个文件，全平铺在一个目录里翻起来很难受。
 *
 * <p>文件名要做清洗。会话 ID 来自调用方（可能是 "群聊/内部测试" 这种带斜杠的名字），
 * 直接拼进路径会创建出意料之外的子目录，甚至突破 base 目录。
 * 这里只保留安全字符，其余换成下划线——宁可文件名难看，也不能让路径逃逸。
 */
public class FileReportArchive implements ReportArchive {

    /** 文件名里保留的字符：字母、数字、下划线、连字符。中文会被替换掉。 */
    private static final String UNSAFE_CHARS = "[^a-zA-Z0-9_\\-]";

    private final Path baseDir;

    public FileReportArchive(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public String write(MonthlyReport report) {
        String fileName = sanitize(report.conversationId()) + ".md";
        Path dir = baseDir.resolve(report.period().label());
        Path file = dir.resolve(fileName);

        try {
            Files.createDirectories(dir);
            // 覆写而不是追加：同一周期重跑应该替换旧文件，否则会看到两版内容拼在一起
            Files.writeString(file, report.markdown(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return file.toString();
        } catch (IOException e) {
            throw new IllegalStateException("写入报表文件失败: " + file, e);
        }
    }

    /** 清洗成安全的文件名片段；全部被替换掉时退化成 "unknown"，避免出现 ".md" 这种隐藏文件。 */
    private static String sanitize(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return "unknown";
        }
        String cleaned = conversationId.replaceAll(UNSAFE_CHARS, "_");
        // 折叠连续下划线，"群聊//测试" 不会变成 "____"
        cleaned = cleaned.replaceAll("_{2,}", "_");
        // 去掉首尾下划线和点，避免 "." ".." 这类特殊名字
        cleaned = cleaned.replaceAll("^[_.]+|[_.]+$", "");
        if (cleaned.isEmpty()) {
            return "unknown";
        }
        // 文件名过长在某些文件系统上会直接失败，留出 ".md" 与目录的余量
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}
