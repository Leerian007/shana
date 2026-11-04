package com.shana.api.vo;

import com.shana.rag.store.RetrievedChunk;

/**
 * 检索命中的片段。
 *
 * @param documentId    所属文档 ID
 * @param documentTitle 文档标题
 * @param content       片段正文
 * @param score         余弦相似度，越接近 1 越相关
 */
public record RetrievedChunkVO(long documentId, String documentTitle, String content, double score) {

    public static RetrievedChunkVO from(RetrievedChunk chunk) {
        return new RetrievedChunkVO(chunk.documentId(), chunk.documentTitle(), chunk.content(), chunk.score());
    }
}
