package com.shana.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/** group_monthly_report 表的持久化对象。 */
@Data
@TableName("group_monthly_report")
public class GroupMonthlyReportEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    /** 周期标签，yyyy-MM */
    private String period;

    private Integer messageCount;

    private Integer activeMembers;

    /** 发言最多的人，无人发言时为 null */
    private String topSender;

    private Integer topSenderMessages;

    /** AI 话题总结，未生成时为空串 */
    private String narrative;

    /** 总结失败原因，成功时为 null */
    private String narrativeError;

    /** 完整 Markdown，保证历史报表可复现 */
    private String markdown;

    private OffsetDateTime generatedAt;
}
