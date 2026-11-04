package com.shana.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shana.infrastructure.persistence.entity.ChunkSearchRow;
import com.shana.infrastructure.persistence.entity.RagChunkEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * rag_chunk 表的 Mapper。
 *
 * <p>向量列不走 MyBatis 类型处理器，而是把 {@code float[]} 格式化成 pgvector 的文本字面量
 * {@code [1,2,3]}，再用 {@code CAST(... AS vector)} 交给数据库解析。
 * 理由：pgvector 的 JDBC 类型扩展需要额外的驱动依赖，而这里的读写点只有三处，
 * 用字符串传参足够，也避免 MyBatis-Plus 在实体上多出一个"不认识 vector"的字段。
 *
 * <p>{@code <=>} 是 pgvector 的余弦距离操作符，值域 [0, 2]；
 * 相似度 = 1 - 距离，与本类的 {@code score} 对应。
 */
public interface RagChunkMapper extends BaseMapper<RagChunkEntity> {

    @Insert("""
            INSERT INTO rag_chunk (document_id, chunk_index, content, embedding)
            VALUES (#{documentId}, #{chunkIndex}, #{content}, CAST(#{embedding} AS vector))
            """)
    int insertVector(@Param("documentId") long documentId,
                     @Param("chunkIndex") int chunkIndex,
                     @Param("content") String content,
                     @Param("embedding") String embedding);

    /**
     * 按余弦相似度检索。
     *
     * <p>阈值写在 WHERE 里而不是查回来再过滤：让数据库先用索引筛掉大部分行，
     * 应用层只处理真正可能用到的结果。
     * <p>{@code ORDER BY} 与 {@code WHERE} 重复写一次 {@code <=>}，是为了让查询计划能直接走 HNSW 索引。
     */
    @Select("""
            SELECT c.id             AS chunk_id,
                   c.document_id     AS document_id,
                   d.title           AS document_title,
                   c.content         AS content,
                   1 - (c.embedding <=> CAST(#{vector} AS vector)) AS score
            FROM rag_chunk c
            JOIN rag_document d ON d.id = c.document_id
            WHERE 1 - (c.embedding <=> CAST(#{vector} AS vector)) >= #{minScore}
            ORDER BY c.embedding <=> CAST(#{vector} AS vector)
            LIMIT #{topK}
            """)
    List<ChunkSearchRow> searchByVector(@Param("vector") String vector,
                                        @Param("topK") int topK,
                                        @Param("minScore") double minScore);

    @Select("SELECT count(*) FROM rag_chunk")
    long countAll();
}
