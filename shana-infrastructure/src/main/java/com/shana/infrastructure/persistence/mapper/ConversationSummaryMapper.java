package com.shana.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shana.infrastructure.persistence.entity.ConversationSummaryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 摘要表访问。
 */
@Mapper
public interface ConversationSummaryMapper extends BaseMapper<ConversationSummaryEntity> {

    /**
     * 写入摘要，但只在库里的游标仍等于 {@code expectedCoveredMessageId} 时才生效。
     *
     * <p>用一条 SQL 同时完成"首次插入"和"乐观更新"：
     * 没有记录时正常 INSERT；已有记录且游标未被别人推进时 UPDATE 命中一行；
     * 游标已被推进（说明另一个实例先完成了）时 UPDATE 的 WHERE 不成立，返回 0。
     *
     * <p>这样不需要先查后写，也就没有"查到旧值再覆盖别人"的窗口期。
     *
     * @return 1 表示写入成功，0 表示已被别人推进、本次结果应当丢弃
     */
    @Insert("""
            INSERT INTO conversation_summary (conversation_id, summary, covered_message_id)
            VALUES (#{conversationId}, #{summary}, #{coveredMessageId})
            ON CONFLICT (conversation_id) DO UPDATE
               SET summary = EXCLUDED.summary,
                   covered_message_id = EXCLUDED.covered_message_id,
                   updated_at = now()
             WHERE conversation_summary.covered_message_id = #{expectedCoveredMessageId}
            """)
    int upsertIfNotAdvanced(@Param("conversationId") String conversationId,
                            @Param("summary") String summary,
                            @Param("coveredMessageId") long coveredMessageId,
                            @Param("expectedCoveredMessageId") long expectedCoveredMessageId);
}
