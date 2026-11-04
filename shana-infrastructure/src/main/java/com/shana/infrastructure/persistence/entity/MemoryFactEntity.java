package com.shana.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * memory_fact 表的持久化对象。
 */
@Data
@TableName("memory_fact")
public class MemoryFactEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;

    /** PERSON / PREFERENCE / DECISION / FACT */
    private String kind;

    /** 主体，无明确主体时为空串（不用 null，否则唯一约束失效） */
    private String subject;

    private String content;

    /** 该信息来自哪条消息，便于回查原文 */
    private Long sourceMessageId;

    private OffsetDateTime createdAt;
}
