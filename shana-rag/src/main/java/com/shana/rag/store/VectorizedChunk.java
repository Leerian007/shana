package com.shana.rag.store;

import com.shana.rag.DocumentChunk;

/**
 * 已经算好向量的片段，等待落库。
 *
 * <p>与 {@link DocumentChunk} 分开而不是给 {@code DocumentChunk} 加一个可空的 {@code embedding} 字段：
 * 可空字段会让"忘了向量化"和"向量化失败"在类型上无法区分，
 * 而这里把 {@code vector} 声明为非空，编译期就保证了入库前一定算过。
 *
 * @param chunk  文本片段
 * @param vector 与 {@code chunk.content()} 对应的稠密向量
 */
public record VectorizedChunk(DocumentChunk chunk, float[] vector) {
}
