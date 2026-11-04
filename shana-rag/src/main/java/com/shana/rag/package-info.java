/**
 * 检索增强生成（RAG）的领域层。
 *
 * <p>本模块只包含三件事：文档怎么切、知识库怎么编排、向量存储需要哪些能力。
 * 具体存在哪里（PostgreSQL + pgvector）由 {@code shana-infrastructure} 实现，
 * 具体用哪个嵌入模型由 {@code shana-model} 的 {@code EmbeddingClient} 提供。
 *
 * <p>因此这里不出现任何 SQL、HTTP 或厂商字段——换向量库或换嵌入模型时本模块不用改。
 */
package com.shana.rag;
