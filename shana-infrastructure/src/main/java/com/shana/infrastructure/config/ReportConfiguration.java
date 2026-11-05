package com.shana.infrastructure.config;

import com.shana.infrastructure.persistence.JdbcGroupActivitySource;
import com.shana.infrastructure.persistence.JdbcReportRepository;
import com.shana.infrastructure.persistence.mapper.GroupMonthlyReportMapper;
import com.shana.infrastructure.persistence.mapper.GroupReportMapper;
import com.shana.infrastructure.report.FileReportArchive;
import com.shana.model.LlmClientRegistry;
import com.shana.model.LlmProvider;
import com.shana.report.GroupActivitySource;
import com.shana.report.MarkdownReportRenderer;
import com.shana.report.MonthlyReportService;
import com.shana.report.NarrativeGenerator;
import com.shana.report.ReportArchive;
import com.shana.report.ReportRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;

/**
 * 报表能力装配。
 *
 * <p>两个前置条件，缺一不可：
 * <ul>
 *   <li>{@code shana.memory.store=jdbc}——只有 jdbc 模式保留全量历史。
 *       Redis / 内存模式下消息会被淘汰，按月统计本身就是错的，此时不装报表，
 *       接口返回"不可用"而不是给一份残缺数据；</li>
 *   <li>{@code shana.report.enabled=true}——显式开关，便于只想跑对话的场景关掉。</li>
 * </ul>
 *
 * <p>归档（写 Markdown 文件）是独立开关：库里的记录是主，文件是副本。
 * 容器化部署时文件系统往往是临时的，关掉归档反而更合理。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shana.memory", name = "store", havingValue = "jdbc", matchIfMissing = true)
@ConditionalOnProperty(prefix = "shana.report", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReportConfiguration {

    @Bean
    GroupActivitySource groupActivitySource(GroupReportMapper groupReportMapper,
                                            @org.springframework.beans.factory.annotation.Value(
                                                    "${shana.report.narrative.max-samples:120}") int maxSamples) {
        return new JdbcGroupActivitySource(groupReportMapper, maxSamples);
    }

    @Bean
    ReportRepository reportRepository(GroupMonthlyReportMapper groupMonthlyReportMapper) {
        return new JdbcReportRepository(groupMonthlyReportMapper);
    }

    @Bean
    MarkdownReportRenderer markdownReportRenderer() {
        return new MarkdownReportRenderer();
    }

    @Bean
    NarrativeGenerator narrativeGenerator(LlmClientRegistry llmClientRegistry,
                                          @org.springframework.beans.factory.annotation.Value(
                                                  "${shana.llm.provider:OPENAI_COMPATIBLE}") LlmProvider provider,
                                          @org.springframework.beans.factory.annotation.Value(
                                                  "${shana.report.narrative.enabled:true}") boolean enabled,
                                          @org.springframework.beans.factory.annotation.Value(
                                                  "${shana.report.narrative.max-samples:120}") int maxSamples) {
        return new NarrativeGenerator(llmClientRegistry, provider, maxSamples, enabled);
    }

    /**
     * 归档是可选能力：没配目录（或显式关闭）时 {@link MonthlyReportService} 拿到 null，
     * 只入库不落文件。用 {@link ObjectProvider} 表达"可以不装"，而不是给个空实现——
     * 空实现会让"没配置"和"写失败"看起来一样。
     */
    @Bean
    @ConditionalOnProperty(prefix = "shana.report.archive", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    ReportArchive reportArchive(Environment environment) {
        String dir = environment.getProperty("shana.report.archive.dir", "./reports");
        return new FileReportArchive(Path.of(dir));
    }

    @Bean
    MonthlyReportService monthlyReportService(GroupActivitySource activitySource,
                                               ReportRepository reportRepository,
                                               MarkdownReportRenderer renderer,
                                               NarrativeGenerator narrativeGenerator,
                                               ObjectProvider<ReportArchive> archiveProvider) {
        return new MonthlyReportService(activitySource, reportRepository, renderer,
                narrativeGenerator, archiveProvider.getIfAvailable());
    }
}
