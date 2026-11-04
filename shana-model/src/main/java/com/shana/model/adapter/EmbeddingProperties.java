package com.shana.model.adapter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI 兼容协议的嵌入模型配置。
 *
 * <p>与对话模型分开配置是刻意的：DeepSeek 只提供 /chat/completions，没有 /embeddings，
 * 所以 {@code shana.llm.*} 那套 key 通常不能复用。这两组配置往往指向不同服务商。
 *
 * @param baseUrl   服务地址，需包含版本前缀，例如 {@code https://api.siliconflow.cn/v1}
 * @param apiKey    鉴权 key，为空时整个 RAG 能力不装配
 * @param model     模型名，例如 {@code BAAI/bge-m3}
 * @param dimension 向量维度，必须与数据库 {@code vector(N)} 一致
 * @param batchSize 单次请求的文本条数，部分服务商对 input 长度有限制
 */
@ConfigurationProperties(prefix = "shana.embedding")
public record EmbeddingProperties(String baseUrl,
                                  String apiKey,
                                  String model,
                                  Integer dimension,
                                  Integer batchSize) {

    /** 建表默认维度：bge-m3、text-embedding-v3 都是 1024，是中文场景最常见的选择。 */
    public static final int DEFAULT_DIMENSION = 1024;

    /** 单批默认 16 条：多数服务商限制在 16~32 之间，取小的那个更安全。 */
    public static final int DEFAULT_BATCH_SIZE = 16;

    public int dimensionOrDefault() {
        return dimension == null || dimension <= 0 ? DEFAULT_DIMENSION : dimension;
    }

    public int batchSizeOrDefault() {
        return batchSize == null || batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
    }
}
