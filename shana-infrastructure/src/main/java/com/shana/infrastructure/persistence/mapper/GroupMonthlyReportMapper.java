package com.shana.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shana.infrastructure.persistence.entity.GroupMonthlyReportEntity;
import org.apache.ibatis.annotations.Insert;

/**
 * group_monthly_report 表的 Mapper。
 *
 * <p>只有 upsert 需要手写：MyBatis-Plus 没有内置的"插入或更新"，
 * 而先 select 再决定 insert/update 会多一次往返，并且在并发重跑时会撞唯一索引。
 * PostgreSQL 的 {@code ON CONFLICT} 一条语句解决，且是原子的。
 */
public interface GroupMonthlyReportMapper extends BaseMapper<GroupMonthlyReportEntity> {

    @Insert("""
            INSERT INTO group_monthly_report
                (conversation_id, period, message_count, active_members, top_sender,
                 top_sender_messages, narrative, narrative_error, markdown, generated_at)
            VALUES
                (#{conversationId}, #{period}, #{messageCount}, #{activeMembers}, #{topSender},
                 #{topSenderMessages}, #{narrative}, #{narrativeError}, #{markdown}, #{generatedAt})
            ON CONFLICT (conversation_id, period) DO UPDATE SET
                message_count       = EXCLUDED.message_count,
                active_members      = EXCLUDED.active_members,
                top_sender          = EXCLUDED.top_sender,
                top_sender_messages = EXCLUDED.top_sender_messages,
                narrative           = EXCLUDED.narrative,
                narrative_error     = EXCLUDED.narrative_error,
                markdown            = EXCLUDED.markdown,
                generated_at        = EXCLUDED.generated_at
            """)
    void upsert(GroupMonthlyReportEntity entity);
}
