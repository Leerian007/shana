package com.shana.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 月度报表编排：取数 → 统计 → 话题总结 → 渲染 → 入库 → 归档。
 *
 * <p>为什么把编排放在 report 模块而不是 core：它不参与对话链路，也不需要认识
 * {@code ChatService}。它只依赖 {@link GroupActivitySource} 和 {@link ReportRepository}
 * 两个端口，具体是 PostgreSQL 还是别的存储由 infrastructure 决定。
 *
 * <p>失败处理分成两个级别，这是有意区分而不是随手写的：
 * <ul>
 *   <li><b>话题总结失败</b>：降级。报表照出，只是少一段叙述，原因写进报表。</li>
 *   <li><b>落盘失败</b>：降级。数据库已经有了，文件只是副本，记日志即可。</li>
 *   <li><b>取数或入库失败</b>：抛出。没有数据就没有报表，没存上就等于没生成，
 *       这两种情况必须让调用方知道，不能假装成功。</li>
 * </ul>
 */
public class MonthlyReportService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyReportService.class);

    private final GroupActivitySource activitySource;
    private final ReportRepository repository;
    private final MarkdownReportRenderer renderer;
    private final NarrativeGenerator narrativeGenerator;
    /** 可以为 null：不配置归档时只入库、不落文件。 */
    private final ReportArchive archive;

    public MonthlyReportService(GroupActivitySource activitySource,
                                ReportRepository repository,
                                MarkdownReportRenderer renderer,
                                NarrativeGenerator narrativeGenerator,
                                ReportArchive archive) {
        this.activitySource = activitySource;
        this.repository = repository;
        this.renderer = renderer;
        this.narrativeGenerator = narrativeGenerator;
        this.archive = archive;
    }

    /**
     * 生成（或重新生成）某个群某个月的报表。
     *
     * <p>重复调用是安全的：入库按 (群, 周期) 覆盖，归档按同名文件覆盖。
     */
    public MonthlyReport generate(String conversationId, ReportPeriod period) {
        GroupActivity activity = activitySource.load(conversationId, period);

        NarrativeGenerator.NarrativeResult narrative = narrativeGenerator.generate(activity);
        OffsetDateTime generatedAt = OffsetDateTime.now(ReportPeriod.ZONE);
        String markdown = renderer.render(activity, narrative.narrative(), narrative.error(), generatedAt);

        MonthlyReport report = MonthlyReport.from(
                activity, narrative.narrative(), narrative.error(), markdown, generatedAt);

        repository.save(report);

        if (archive != null) {
            try {
                archive.write(report);
            } catch (Exception e) {
                log.warn("群 {} 的 {} 报表归档失败（数据库记录已写入）: {}",
                        conversationId, period.label(), e.toString());
            }
        }
        return report;
    }

    /**
     * 批量生成某个月所有活跃群的报表。
     *
     * <p>单个群失败不中断整批：一个月报任务跑了 20 个群，不能因为第 3 个群的模型调用超时
     * 就让剩下的 17 个都没有报表。失败清单随结果一起返回，由调用方决定要不要重试。
     */
    public BatchResult generateAll(ReportPeriod period) {
        List<String> conversations = activitySource.conversationsActiveIn(period);
        List<MonthlyReport> succeeded = new ArrayList<>(conversations.size());
        List<Failure> failed = new ArrayList<>();

        for (String conversationId : conversations) {
            try {
                succeeded.add(generate(conversationId, period));
            } catch (Exception e) {
                log.warn("群 {} 的 {} 报表生成失败，继续处理其它群: {}",
                        conversationId, period.label(), e.toString());
                failed.add(new Failure(conversationId, e.toString()));
            }
        }

        log.info("{} 报表批量生成完成：成功 {} 个群，失败 {} 个群",
                period.label(), succeeded.size(), failed.size());
        return new BatchResult(period, succeeded, failed);
    }

    /** 批量结果。失败清单不为空时调用方应提示用户，而不是只报告"完成"。 */
    public record BatchResult(ReportPeriod period,
                              List<MonthlyReport> succeeded,
                              List<Failure> failed) {

        public BatchResult {
            succeeded = List.copyOf(succeeded);
            failed = List.copyOf(failed);
        }

        public int total() {
            return succeeded.size() + failed.size();
        }
    }

    public record Failure(String conversationId, String reason) {
    }
}
