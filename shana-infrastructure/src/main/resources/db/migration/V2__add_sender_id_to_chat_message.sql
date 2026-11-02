-- 为消息补充"发送者"维度
--
-- 背景：V1 只有 role（system / user / assistant / tool）。role 是模型协议概念，
-- 群聊里所有成员共用 user 一个值，导致"谁说的"在落库时就丢了，
-- 事后无法按人聚合，也无法做月度群友总结。
--
-- 设计说明：
-- 1. 允许为空，且不回填历史数据。V2 之前写入的消息确实没采集过发送者，
--    编造一个默认值只会污染后续统计（分不清"真的没传"和"就是这个人"）。
--    分析时用 sender_id IS NULL 显式排除即可。
-- 2. 不建外键。sender_id 来自外部平台（如微信 wxid），可能被改号、可能指向
--    本系统尚未建档的成员，硬约束会让消息写入因"用户不存在"而失败——
--    采集端不该因为它丢消息。成员的内部主键与外部标识的映射留给后续的成员表。
-- 3. 字段不参与问答链路：它只是旁路留存的数据，拼 prompt 时不会读取。
ALTER TABLE chat_message ADD COLUMN sender_id VARCHAR(64);

COMMENT ON COLUMN chat_message.sender_id IS '发送者标识（群成员 ID），由接入端提供；system/assistant 消息为空';

-- 高频分析查询是"某人在某群的发言"，按 (会话, 发送者, 主键倒序) 建索引可避免全表扫
CREATE INDEX idx_chat_message_sender_id
    ON chat_message (conversation_id, sender_id, id DESC);
