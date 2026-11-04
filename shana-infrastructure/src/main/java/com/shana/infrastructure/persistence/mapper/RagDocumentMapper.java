package com.shana.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shana.infrastructure.persistence.entity.RagDocumentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * rag_document 表的 Mapper。
 *
 * <p>写入用 {@code ON CONFLICT DO NOTHING} 而不是先查再插：
 * 多实例同时上传同一份文档时，"先查后插"必然有竞态，
 * 而 PG 在事务里一旦抛出唯一约束冲突，整个事务就进入 aborted 状态，catch 之后连查询都做不了。
 */
public interface RagDocumentMapper extends BaseMapper<RagDocumentEntity> {

    /**
     * 按内容指纹插入，冲突时静默跳过。
     *
     * @return 1 表示插入成功，0 表示已存在（此时 {@code entity.id} 不会被回填，需要再查一次）
     */
    @Insert("""
            INSERT INTO rag_document (title, source, content_hash, chunk_count)
            VALUES (#{title}, #{source}, #{contentHash}, #{chunkCount})
            ON CONFLICT (content_hash) DO NOTHING
            """)
    int insertIgnoreConflict(RagDocumentEntity entity);

    @Select("SELECT id FROM rag_document WHERE content_hash = #{contentHash}")
    Long selectIdByHash(@Param("contentHash") String contentHash);
}
