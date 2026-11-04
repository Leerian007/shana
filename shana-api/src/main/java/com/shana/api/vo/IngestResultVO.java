package com.shana.api.vo;

import com.shana.rag.IngestResult;

/**
 * 文档入库结果。
 *
 * @param documentId 文档 ID；重复上传时是已存在的那篇
 * @param chunkCount 本次切出的片段数，重复上传时为 0
 * @param duplicate  是否命中内容指纹去重（同一份内容重复上传不会重复花钱向量化）
 */
public record IngestResultVO(long documentId, int chunkCount, boolean duplicate) {

    public static IngestResultVO from(IngestResult result) {
        return new IngestResultVO(result.documentId(), result.chunkCount(), result.duplicate());
    }
}
