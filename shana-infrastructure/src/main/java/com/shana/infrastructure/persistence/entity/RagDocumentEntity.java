package com.shana.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * rag_document 表的持久化对象。
 *
 * <p>注意这里没有 content 字段：正文被切成片段存在 {@code rag_chunk} 里。
 * 原文本不进库是刻意的——它没有任何查询用途，只会在库里多占一份空间。
 * 需要原文时从来源（文件、URL）重新导入即可，内容指纹能保证幂等。
 */
@Data
@TableName("rag_document")
public class RagDocumentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String source;

    /** 正文 SHA-256，唯一索引列 */
    private String contentHash;

    private Integer chunkCount;

    private OffsetDateTime createdAt;
}
