package com.shana.infrastructure.persistence.mapper;

import com.shana.infrastructure.persistence.dto.ReportAggregates;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 报表聚合查询。
 *
 * <p>SQL 写在 {@code resources/mapper/GroupReportMapper.xml}：这几条语句都有窗口函数和
 * 时区转换，塞进注解里会挤成一团，改的时候容易漏看条件。
 *
 * <p>所有查询都带同一个过滤口径（见 XML 里的 {@code memberMessages} 片段），
 * 保证"总条数"与"成员条数之和"永远一致。分散写条件最容易出现的是：
 * 总数统计漏了 {@code sender_id IS NOT NULL}，于是人均发言算出来对不上。
 */
public interface GroupReportMapper {

    List<ReportAggregates.MemberRow> aggregateByMember(@Param("conversationId") String conversationId,
                                                       @Param("from") OffsetDateTime from,
                                                       @Param("to") OffsetDateTime to);

    List<ReportAggregates.DailyRow> aggregateByDay(@Param("conversationId") String conversationId,
                                                   @Param("from") OffsetDateTime from,
                                                   @Param("to") OffsetDateTime to);

    List<ReportAggregates.HourlyRow> aggregateByHour(@Param("conversationId") String conversationId,
                                                     @Param("from") OffsetDateTime from,
                                                     @Param("to") OffsetDateTime to);

    /** 按时间均匀抽样：不是取前 N 条，否则样本只代表月初。 */
    List<ReportAggregates.SampleRow> sampleMessages(@Param("conversationId") String conversationId,
                                                    @Param("from") OffsetDateTime from,
                                                    @Param("to") OffsetDateTime to,
                                                    @Param("limit") int limit);

    /** 该周期内有成员发言的群，供批量任务决定跑哪些群。 */
    List<String> conversationsActiveIn(@Param("from") OffsetDateTime from,
                                       @Param("to") OffsetDateTime to);
}
