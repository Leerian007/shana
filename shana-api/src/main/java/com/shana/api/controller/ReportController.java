package com.shana.api.controller;

import com.shana.api.vo.BatchReportVO;
import com.shana.api.vo.MonthlyReportSummaryVO;
import com.shana.api.vo.MonthlyReportVO;
import com.shana.common.api.ErrorCode;
import com.shana.common.api.Result;
import com.shana.common.exception.BusinessException;
import com.shana.report.MonthlyReport;
import com.shana.report.MonthlyReportService;
import com.shana.report.ReportPeriod;
import com.shana.report.ReportRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 群聊月度报表接口。
 *
 * <p>生成与查询分开：POST 真的会跑聚合、调模型、写文件，耗时随群规模增长（单群几秒）；
 * GET 只读库，毫秒级。混在一个接口里会让"看一眼上个月的报表"变成一次昂贵操作。
 *
 * <p>{@code period} 统一可选，缺省为上月。让调用方每次显式传 "2026-10" 是件容易出错的事，
 * 而"查看上个月"是最常见的用法。
 *
 * <p>用 {@link ObjectProvider} 取服务而不是构造器强依赖：{@code shana.memory.store=redis}
 * 时报表能力压根不装配（没有全量历史，按月统计不成立），此时接口应返回 503 而不是让整个
 * 应用起不来。
 */
@RestController
@RequestMapping("/api/v1/reports/monthly")
public class ReportController {

    private final ObjectProvider<MonthlyReportService> serviceProvider;
    private final ObjectProvider<ReportRepository> repositoryProvider;

    public ReportController(ObjectProvider<MonthlyReportService> serviceProvider,
                            ObjectProvider<ReportRepository> repositoryProvider) {
        this.serviceProvider = serviceProvider;
        this.repositoryProvider = repositoryProvider;
    }

    /** 为所有该月活跃的群批量生成报表。 */
    @PostMapping
    public Result<BatchReportVO> generateAll(@RequestParam(required = false) String period) {
        MonthlyReportService service = service();
        return Result.success(BatchReportVO.from(service.generateAll(parsePeriod(period))));
    }

    /** 为指定群生成（或重新生成）报表。重跑是覆盖语义。 */
    @PostMapping("/{conversationId}")
    public Result<MonthlyReportVO> generate(@PathVariable String conversationId,
                                            @RequestParam(required = false) String period) {
        MonthlyReportService service = service();
        MonthlyReport report = service.generate(conversationId, parsePeriod(period));
        return Result.success(MonthlyReportVO.from(report));
    }

    /** 某月所有群的报表摘要，按发言量降序。 */
    @GetMapping
    public Result<List<MonthlyReportSummaryVO>> list(@RequestParam(required = false) String period) {
        List<MonthlyReportSummaryVO> reports = repository().findAll(parsePeriod(period)).stream()
                .map(MonthlyReportSummaryVO::from)
                .toList();
        return Result.success(reports);
    }

    /** 某个群某月的报表全文。 */
    @GetMapping("/{conversationId}")
    public Result<MonthlyReportVO> detail(@PathVariable String conversationId,
                                          @RequestParam(required = false) String period) {
        MonthlyReport report = repository()
                .find(conversationId, parsePeriod(period))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "该群在 " + parsePeriod(period).label() + " 没有报表，请先生成"));
        return Result.success(MonthlyReportVO.from(report));
    }

    private MonthlyReportService service() {
        MonthlyReportService service = serviceProvider.getIfAvailable();
        if (service == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "报表能力未启用：需要 shana.memory.store=jdbc（只有 jdbc 模式保留全量历史）"
                            + "且 shana.report.enabled=true");
        }
        return service;
    }

    private ReportRepository repository() {
        ReportRepository repository = repositoryProvider.getIfAvailable();
        if (repository == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "报表能力未启用：需要 shana.memory.store=jdbc 且 shana.report.enabled=true");
        }
        return repository;
    }

    /** 格式错误转成 400，而不是让它变成 500。 */
    private static ReportPeriod parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            return ReportPeriod.previousMonth();
        }
        try {
            return ReportPeriod.parse(period);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }
}
