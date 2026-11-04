package com.shana.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * rag_chunk 表的持久化对象。
 *
 * <p>刻意<b>不包含</b> {@code embedding} 字段：向量列的类型是 pgvector 的 {@code vector}，
 * MyBatis 默认类型处理器不认识它，映射进来只会报错。
 * 向量的写入与检索都在 {@code RagChunkMapper} 的注解 SQL 里完成，
 * 需要向量时也不会经过这个实体——检索结果只返回标量列。
 */
@Data
@TableName("rag_chunk")
public class RagChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Integer chunkIndex;

    private String content;

    private OffsetDateTime createdAt;
}
