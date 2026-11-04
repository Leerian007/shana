package com.shana.rag;

/**
 * 入库结果。
 *
 * @param documentId 文档 ID；{@code duplicate=true} 时是已存在的那篇
 * @param chunkCount 本次切出的片段数；重复上传时为 0
 * @param duplicate  是否命中内容指纹去重
 */
public record IngestResult(long documentId, int chunkCount, boolean duplicate) {
}
