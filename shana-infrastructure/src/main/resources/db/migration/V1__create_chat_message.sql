-- 对话消息表
--
-- 设计说明：
-- 1. 没有单独建 conversation 表。当前没有任何会话级属性需要持久化
--    （群名、人设 prompt 等都还没做），conversation_id 直接作为字符串存在消息表里。
--    等真正需要按群配置时再加表，避免先建一张只做外键的空表。
-- 2. 主键用自增 BIGSERIAL 而不是时间戳排序：同一毫秒内写入多条消息时，
--    created_at 无法定序，自增主键能稳定还原先后顺序。
-- 3. 不做"超窗口删除"。内存实现为了省内存会淘汰旧消息，
--    但数据库里保留完整历史，长期记忆、群聊月度总结都要用到。
--    窗口只限制"读多少条"，由查询时的 LIMIT 控制。
CREATE TABLE chat_message
(
    id              BIGSERIAL   PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    role            VARCHAR(16) NOT NULL,
    content         TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE chat_message IS '群聊对话消息';
COMMENT ON COLUMN chat_message.conversation_id IS '会话/群聊标识，由调用方提供';
COMMENT ON COLUMN chat_message.role IS '角色：system / user / assistant / tool';

-- 取最近 N 条消息是最高频查询，按 (会话, 主键倒序) 建索引可避免排序
CREATE INDEX idx_chat_message_conversation_id_desc
    ON chat_message (conversation_id, id DESC);
