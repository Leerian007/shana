package com.shana.infrastructure.persistence.entity;

import lombok.Data;

/**
 * 向量检索的结果行。
 *
 * <p>不是表实体，只是为了承接 {@code SELECT ... AS score} 的结果。
 * 用可变 POJO 而不是 record：MyBatis 的构造器自动映射需要额外开启参数名匹配，
 * 换一个配置就会静默失败，用 setter 映射最稳。
 */
@Data
public class ChunkSearchRow {

    private Long chunkId;

    private Long documentId;

    private String documentTitle;

    private String content;

    /** 余弦相似度 = 1 - 余弦距离，取值 [-1, 1]，越大越相关 */
    private Double score;
}
