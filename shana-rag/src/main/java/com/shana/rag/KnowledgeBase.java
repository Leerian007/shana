package com.shana.rag;

import com.shana.rag.store.RetrievedChunk;

import java.util.List;

/**
 * 知识库：文档入库与相似度检索。
 *
 * <p>与"群聊记忆"是两种东西，刻意不合并：
 * 记忆是模型自己聊出来的、会滚动、按会话隔离；
 * 知识库是人给的资料、只追加、全局共享。混在一起会导致"删掉一份错误文档"变成"删掉一段对话记忆"。
 */
public interface KnowledgeBase {

    /** 切分 + 向量化 + 落库，同一份内容重复上传会被指纹挡住。 */
    IngestResult ingest(IngestCommand command);

    /**
     * 检索与问题最相关的片段。
     *
     * @param query 用户问题原文
     * @param topK  最多返回条数，小于等于 0 时用配置默认值
     * @return 按相似度降序；没有达标结果时返回空列表，而不是抛异常
     */
    List<RetrievedChunk> search(String query, int topK);

    /** 删除文档及其片段。 */
    boolean delete(long documentId);

    /** 当前片段总数。 */
    long countChunks();
}
