package com.shana.rag.store;

/**
 * 文档写入结果。
 *
 * <p>需要区分"新建"和"已存在"，是因为并发上传同一份文档时 {@code saveDocument} 可能什么都没写：
 * 两个请求都在查重阶段没查到，真正写入时由唯一索引挡住一个。
 * 这种情况下不能返回"插了 N 个 chunk"，否则调用方会以为库里有两份。
 *
 * @param documentId 文档 ID（新建的或已存在的）
 * @param created    本次是否真的写入了片段
 */
public record SaveOutcome(long documentId, boolean created) {
}
