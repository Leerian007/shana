package com.shana.report;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 编排层测试，重点全是"失败时会发生什么"。
 *
 * <p>正常路径在真机验证里已经跑过；这里要固定住的是降级边界：
 * 哪些失败应该被吞掉（报表照出），哪些必须抛出去（不能假装成功）。
 * 这类语义靠读代码很容易看漏，靠测试才守得住。
 */
class MonthlyReportServiceTest {

    private final ReportPeriod period = ReportPeriod.parse("2026-11");

    @Test
    @DisplayName("正常流程：渲染并入库，返回带 Markdown 的报表")
    void shouldGenerateAndSave() {
        FakeRepository repository = new FakeRepository();
        MonthlyReportService service = service(repository, NarrativeGenerator.NarrativeResult.ok("- 讨论了发布节奏"), null);

        MonthlyReport report = service.generate("g1", period);

        assertThat(report.conversationId()).isEqualTo("g1");
        assertThat(report.messageCount()).isEqualTo(3);
        assertThat(report.markdown()).contains("群聊月度报告");
        assertThat(report.narrative()).isEqualTo("- 讨论了发布节奏");
        assertThat(repository.saved).hasSize(1);
    }

    @Test
    @DisplayName("话题总结失败不影响报表：markdown 照出，原因写进报表")
    void shouldStillProduceReportWhenNarrativeFails() {
        FakeRepository repository = new FakeRepository();
        MonthlyReportService service = service(repository,
                NarrativeGenerator.NarrativeResult.failed("调用模型失败：timeout"), null);

        MonthlyReport report = service.generate("g1", period);

        assertThat(report.messageCount()).isEqualTo(3);
        assertThat(report.hasNarrative()).isFalse();
        assertThat(report.narrativeError()).isEqualTo("调用模型失败：timeout");
        assertThat(report.markdown()).contains("调用模型失败：timeout");
        assertThat(repository.saved).hasSize(1);
    }

    @Test
    @DisplayName("归档失败被吞掉：数据库已写入，不应因为写文件失败而报错")
    void shouldSwallowArchiveFailure() {
        FakeRepository repository = new FakeRepository();
        ReportArchive brokenArchive = report -> {
            throw new IllegalStateException("磁盘只读");
        };
        MonthlyReportService service = service(repository,
                NarrativeGenerator.NarrativeResult.ok("- 话题"), brokenArchive);

        MonthlyReport report = service.generate("g1", period);

        assertThat(report.markdown()).isNotBlank();
        assertThat(repository.saved).hasSize(1);
    }

    @Test
    @DisplayName("入库失败必须抛出：没存上就等于没生成")
    void shouldPropagateRepositoryFailure() {
        ReportRepository broken = new FakeRepository() {
            @Override
            public void save(MonthlyReport report) {
                throw new IllegalStateException("数据库连接断开");
            }
        };
        MonthlyReportService service = service(broken, NarrativeGenerator.NarrativeResult.ok("- 话题"), null);

        assertThatThrownBy(() -> service.generate("g1", period))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据库连接断开");
    }

    @Test
    @DisplayName("批量生成时单个群失败不中断，失败清单带回调用方")
    void shouldContinueBatchWhenOneGroupFails() {
        FakeRepository repository = new FakeRepository();
        GroupActivitySource flakySource = new FakeSource(List.of("g1", "g2", "g3")) {
            @Override
            public GroupActivity load(String conversationId, ReportPeriod p) {
                if ("g2".equals(conversationId)) {
                    throw new IllegalStateException("查询超时");
                }
                return super.load(conversationId, p);
            }
        };
        MonthlyReportService service = new MonthlyReportService(flakySource, repository,
                new MarkdownReportRenderer(),
                stubNarrative(NarrativeGenerator.NarrativeResult.ok("- 话题")), null);

        MonthlyReportService.BatchResult result = service.generateAll(period);

        assertThat(result.succeeded()).extracting(MonthlyReport::conversationId)
                .containsExactly("g1", "g3");
        assertThat(result.failed()).extracting(MonthlyReportService.Failure::conversationId)
                .containsExactly("g2");
        assertThat(result.total()).isEqualTo(3);
        assertThat(repository.saved).hasSize(2);
    }

    private MonthlyReportService service(ReportRepository repository,
                                         NarrativeGenerator.NarrativeResult narrative,
                                         ReportArchive archive) {
        return new MonthlyReportService(new FakeSource(List.of("g1")), repository,
                new MarkdownReportRenderer(), stubNarrative(narrative), archive);
    }

    private NarrativeGenerator stubNarrative(NarrativeGenerator.NarrativeResult result) {
        NarrativeGenerator generator = mock(NarrativeGenerator.class);
        when(generator.generate(any())).thenReturn(result);
        return generator;
    }

    /** 数据源替身：给固定的一天三行消息，够渲染出完整报表即可。 */
    private static class FakeSource implements GroupActivitySource {

        private final List<String> conversations;

        FakeSource(List<String> conversations) {
            this.conversations = conversations;
        }

        @Override
        public GroupActivity load(String conversationId, ReportPeriod period) {
            return new GroupActivity(conversationId, period, 3, 30,
                    List.of(new MemberActivity("u_a", 3, 30,
                            OffsetDateTime.parse("2026-11-02T10:00:00+08:00"),
                            OffsetDateTime.parse("2026-11-02T10:05:00+08:00"))),
                    List.of(new DailyVolume(LocalDate.of(2026, 11, 2), 3)),
                    List.of(new HourlyVolume(10, 3)),
                    List.of(new SampleMessage("u_a", "你好", OffsetDateTime.parse("2026-11-02T10:00:00+08:00"))));
        }

        @Override
        public List<String> conversationsActiveIn(ReportPeriod period) {
            return conversations;
        }
    }

    /** 内存仓库替身，记录写入以便断言。 */
    private static class FakeRepository implements ReportRepository {

        private final List<MonthlyReport> saved = new ArrayList<>();

        @Override
        public void save(MonthlyReport report) {
            saved.add(report);
        }

        @Override
        public Optional<MonthlyReport> find(String conversationId, ReportPeriod period) {
            return saved.stream()
                    .filter(r -> r.conversationId().equals(conversationId) && r.period().equals(period))
                    .findFirst();
        }

        @Override
        public List<MonthlyReport> findAll(ReportPeriod period) {
            return saved.stream().filter(r -> r.period().equals(period)).toList();
        }
    }
}
