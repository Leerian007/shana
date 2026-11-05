package com.shana.infrastructure.report;

import com.shana.report.MonthlyReportService;
import com.shana.report.ReportPeriod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每月自动生成上月报表。
 *
 * <p>为什么是"上月"而不是"当月"：月报的语义是完整自然月。1 号凌晨跑当月，
 * 只会有几个小时的数据。
 *
 * <p>默认 1 号 02:30，避开整点：整点是各类系统定时任务的集中时段，
 * 数据库和模型服务都可能被别的任务占着。
 *
 * <p><b>多实例会重复跑。</b>没有加分布式锁——重跑是幂等的（入库 upsert、文件覆盖），
 * 结果完全一致，代价只是多花几次模型调用。为此引入 Redis 锁或 ShedLock，
 * 换来的是"锁服务挂了月报就不生成"的新故障模式，不划算。
 * 真要避免，部署时只让一个实例开 {@code shana.report.schedule.enabled=true}。
 */
@Component
@ConditionalOnProperty(prefix = "shana.report.schedule", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MonthlyReportJob {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReportJob.class);

    private final ObjectProvider<MonthlyReportService> serviceProvider;

    public MonthlyReportJob(ObjectProvider<MonthlyReportService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Scheduled(cron = "${shana.report.schedule.cron:0 30 2 1 * ?}")
    public void generateLastMonth() {
        MonthlyReportService service = serviceProvider.getIfAvailable();
        if (service == null) {
            // 报表能力没装配（Redis 模式或显式关闭），定时任务退化为空转
            log.debug("报表能力未装配，跳过本月定时任务");
            return;
        }

        ReportPeriod period = ReportPeriod.previousMonth();
        long startedAt = System.currentTimeMillis();
        try {
            MonthlyReportService.BatchResult result = service.generateAll(period);
            log.info("定时月报完成 {}：成功 {} 个群，失败 {} 个群，耗时 {} ms",
                    period.label(), result.succeeded().size(), result.failed().size(),
                    System.currentTimeMillis() - startedAt);
            result.failed().forEach(f ->
                    log.warn("  失败的群 {}：{}", f.conversationId(), f.reason()));
        } catch (Exception e) {
            // 定时任务抛异常只会被调度线程吞掉并打一行栈，这里主动记清楚是哪个月失败了
            log.error("定时月报 {} 整体失败: {}", period.label(), e.toString(), e);
        }
    }
}
