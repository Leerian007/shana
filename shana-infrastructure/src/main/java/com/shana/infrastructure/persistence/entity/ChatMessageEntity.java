package com.shana.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * chat_message 表的持久化对象。
 *
 * <p>它是数据访问层的内部结构，只在 infrastructure 内部流转，
 * 不向上暴露给 core：上层只认识 {@link com.shana.model.ChatMessage}。
 * 这样以后改表结构（比如拆表、加字段）不会波及业务代码。
 */
@Data
@TableName("chat_message")
public class ChatMessageEntity {

    /** 自增主键，同时用于稳定排序（同一毫秒内的消息靠它区分先后） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话/群聊标识 */
    private String conversationId;

    /** 角色，存小写字符串：system / user / assistant / tool */
    private String role;

    private String content;

    private OffsetDateTime createdAt;
}
