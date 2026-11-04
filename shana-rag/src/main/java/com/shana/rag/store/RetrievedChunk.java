package com.shana.rag.store;

/**
 * 检索命中的片段。
 *
 * @param chunkId       片段 ID，便于排查与后续"引用来源"
 * @param documentId    所属文档 ID
 * @param documentTitle 文档标题，拼 prompt 时用来标注出处
 * @param content       片段正文
 * @param score         余弦相似度，越接近 1 越相关
 */
public record RetrievedChunk(long chunkId, long documentId, String documentTitle, String content, double score) {
}
