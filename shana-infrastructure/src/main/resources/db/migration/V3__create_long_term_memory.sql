-- 长期记忆：对话摘要 + 关键信息抽取
--
-- 为什么要两张表而不是一张：
-- 1. 摘要（conversation_summary）是滚动更新的叙事，每次只保留最新一版，会丢细节。
-- 2. 关键信息（memory_fact）是追加式的事实条目，不会因为摘要滚动被抹掉。
--    两者分工不同：摘要回答"最近群里发生了什么"，事实回答"群里有哪些长期有效的信息"。
--
-- 为什么摘要表用 conversation_id 直接做主键：
-- 一个群只保留一版摘要。历史版本没有查询需求，需要追溯时看 chat_message 原文即可，
-- 额外存版本表只会让"当前摘要"的读取多一层过滤。

CREATE TABLE conversation_summary
(
    conversation_id    VARCHAR(64) PRIMARY KEY,
    summary            TEXT        NOT NULL,
    -- 已经消化到哪条消息：并发重复摘要时靠它做乐观并发控制，也是"增量摘要"的游标
    covered_message_id BIGINT      NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE conversation_summary IS '群聊长期记忆：滚动摘要';
COMMENT ON COLUMN conversation_summary.covered_message_id IS '本摘要已覆盖到的最大消息 ID';

CREATE TABLE memory_fact
(
    id                BIGSERIAL    PRIMARY KEY,
    conversation_id   VARCHAR(64)  NOT NULL,
    kind              VARCHAR(16)  NOT NULL,
    -- 特意不用 NULL 表示"无主体"：PostgreSQL 里 NULL 不参与唯一约束，
    -- 同一个事实无主体时会被反复插入。"没有主体"用空字符串表达。
    subject           VARCHAR(64)  NOT NULL DEFAULT '',
    content           VARCHAR(200) NOT NULL,
    source_message_id BIGINT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 幂等键：模型对同一段对话重复抽取时，措辞一致就不会写重
    CONSTRAINT uq_memory_fact UNIQUE (conversation_id, kind, subject, content)
);

COMMENT ON TABLE memory_fact IS '群聊长期记忆：抽取出的关键信息条目';
COMMENT ON COLUMN memory_fact.kind IS 'PERSON（人物）/ PREFERENCE（偏好）/ DECISION（约定）/ FACT（其它事实）';
COMMENT ON COLUMN memory_fact.subject IS '信息主体，通常是成员名；无明确主体时为空串';

CREATE INDEX idx_memory_fact_conversation_id_desc
    ON memory_fact (conversation_id, id DESC);
