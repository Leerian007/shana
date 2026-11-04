package com.shana.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shana.infrastructure.persistence.entity.MemoryFactEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 关键信息表访问。
 */
@Mapper
public interface MemoryFactMapper extends BaseMapper<MemoryFactEntity> {

    /**
     * 插入一条关键信息，重复时静默忽略。
     *
     * <p>去重交给数据库的唯一约束而不是"先查再插"：模型对同一段对话重复抽取是完全正常的，
     * 先查后插在并发下依然会写重，还多一次往返。
     *
     * @return 1 表示写入，0 表示已存在
     */
    @Insert("""
            INSERT INTO memory_fact (conversation_id, kind, subject, content, source_message_id)
            VALUES (#{conversationId}, #{kind}, #{subject}, #{content}, #{sourceMessageId})
            ON CONFLICT ON CONSTRAINT uq_memory_fact DO NOTHING
            """)
    int insertIgnoreDuplicate(@Param("conversationId") String conversationId,
                              @Param("kind") String kind,
                              @Param("subject") String subject,
                              @Param("content") String content,
                              @Param("sourceMessageId") Long sourceMessageId);
}
