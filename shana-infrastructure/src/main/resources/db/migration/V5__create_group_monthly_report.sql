-- 群聊月度报表
--
-- 为什么要把报表落库，而不是只写 Markdown 文件：
-- 文件适合人看，不适合系统查。"上个月哪些群最活跃""某个群三个月趋势"这类问题
-- 需要按 period 检索，没有表就只能去翻目录、解析文件名。
--
-- 为什么存 markdown 全文而不是只存统计明细：
-- 报表是快照，一旦发出就应该可复现。存全文意味着半年后看到的 11 月报表
-- 和当时发出去的完全一致；如果只存明细再重新渲染，渲染逻辑一改，历史报表就变了。
-- 代价是几 KB 冗余，可以接受。
--
-- 为什么 narrative 和 narrative_error 分开两列：
-- "没有话题总结"和"总结失败"是两件事。前者是配置关闭，后者是模型出事。
-- 混成一列 null 会让运维分不清该不该去查模型。

CREATE TABLE group_monthly_report
(
    id                  BIGSERIAL   PRIMARY KEY,
    conversation_id     VARCHAR(64) NOT NULL,
    -- 报表周期，形如 2026-11。用 CHAR(7) 而不是 date：这是"月份"这个标签，
    -- 不是某一天；用 date 存每月 1 号会让人误以为它代表具体日期
    period              CHAR(7)     NOT NULL,
    message_count       INT         NOT NULL,
    active_members      INT         NOT NULL,
    -- 发言最多的人；整月无人发言时为 NULL
    top_sender          VARCHAR(64),
    top_sender_messages INT         NOT NULL DEFAULT 0,
    -- AI 归纳的话题总结，未启用或失败时为空串
    narrative           TEXT        NOT NULL DEFAULT '',
    -- 总结失败原因，成功时为 NULL
    narrative_error     VARCHAR(500),
    markdown            TEXT        NOT NULL,
    generated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE group_monthly_report IS '群聊月度报表快照';
COMMENT ON COLUMN group_monthly_report.period IS '统计周期，格式 yyyy-MM';
COMMENT ON COLUMN group_monthly_report.narrative IS 'AI 归纳的本月话题，可能为空';
COMMENT ON COLUMN group_monthly_report.markdown IS '生成时的完整 Markdown 报表，用于保证历史报表可复现';

-- 一个群一个月只保留一份。重跑走覆盖而不是追加：
-- 否则查询接口要面对"同一周期有四份报表该返回哪份"的问题
CREATE UNIQUE INDEX uk_group_monthly_report_period
    ON group_monthly_report (conversation_id, period);

-- 按周期查全部群是接口的主要读法，顺带按发言量排序，省一次内存排序
CREATE INDEX idx_group_monthly_report_period
    ON group_monthly_report (period, message_count DESC);
