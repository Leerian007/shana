package com.shana.rag.store;

import com.shana.rag.DocumentChunk;

import java.util.List;
import java.util.Optional;

/**
 * 向量存储端口。
 *
 * <p>只声明知识库真正需要的五件事：查重、写入、检索、删除、计数。
 * 不暴露"建索引""调参"这类实现细节——那些属于具体存储的部署问题，
 * 一旦写进端口，换向量库时就得改业务代码。
 */
public interface VectorStore {

    /** 按内容指纹查已存在的文档 ID。用于上传幂等：同一份文档重复上传不该重复花钱向量化。 */
    Optional<Long> findDocumentIdByHash(String contentHash);

    /**
     * 写入一篇文档及其所有片段（必须在一个事务内完成）。
     *
     * <p>拆成两半写会出现"文档记录存在但没有任何 chunk"的坏数据，
     * 检索永远命中不了，排查时又看不出问题。
     *
     * <p>实现必须做到并发安全：内容指纹上要有唯一约束，重复写入时返回已存在的 ID 而不是报错。
     * 这里不抛冲突异常，是因为调用方在竞态下无法区分"真的失败了"和"别人抢先写了"。
     */
    SaveOutcome saveDocument(String title, String source, String contentHash, List<VectorizedChunk> chunks);

    /**
     * 向量相似度检索。
     *
     * @param queryVector 查询向量，维度必须与存储维度一致
     * @param topK        最多返回条数
     * @param minScore    相似度下限（余弦相似度，越大越相似），低于此值的直接丢弃
     */
    List<RetrievedChunk> search(float[] queryVector, int topK, double minScore);

    /** 删除文档及其所有片段，返回是否真的删掉了记录。 */
    boolean deleteDocument(long documentId);

    /** 当前库里有多少片段，用于暴露"知识库是否为空"给接口与日志。 */
    long countChunks();
}
