package com.shana.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * conversation_summary 表的持久化对象。
 *
 * <p>主键就是会话 ID：一个群只保留一版当前摘要，历史版本没有查询需求。
 */
@Data
@TableName("conversation_summary")
public class ConversationSummaryEntity {

    @TableId(type = IdType.INPUT)
    private String conversationId;

    private String summary;

    /** 已消化到的最大消息 ID，同时充当摘要游标与乐观锁版本 */
    private Long coveredMessageId;

    private OffsetDateTime updatedAt;
}
