package com.shana.report;

import java.time.OffsetDateTime;

/**
 * 喂给模型做话题归纳的样本消息。
 *
 * <p>只是"样本"，不是全量：一个月几万条消息既塞不进上下文，也没有必要——
 * 归纳"这个月在聊什么"靠的是覆盖面，不是穷举。取数时按时间均匀抽取，
 * 比取"最后 N 条"更能代表整月（后者只会反映月末的话题）。
 *
 * @param content 已截断的正文，超长消息只保留开头
 */
public record SampleMessage(String senderId, String content, OffsetDateTime createdAt) {
}
