package com.shana.model.adapter;

import com.shana.model.EmbeddingClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 条件装配向量化适配器。
 *
 * <p>没有配 {@code shana.embedding.api-key} 时容器里就没有 {@link EmbeddingClient}，
 * 知识库的入库与检索接口会返回"未配置向量化模型"，但问答链路照常工作。
 * 这与对话适配器的策略一致：<b>缺配置不是启动失败，而是明确的功能缺失</b>。
 *
 * <p>为什么单独一套 key：DeepSeek 没有 /embeddings 接口，对话与向量化通常来自不同厂商。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${shana.embedding.api-key:}'.length() > 0")
class OpenAiCompatibleEmbeddingConfiguration {

    @Bean
    EmbeddingClient openAiCompatibleEmbeddingClient(EmbeddingProperties properties,
                                                    RestClient.Builder restClientBuilder) {
        return OpenAiCompatibleEmbeddingClient.create(properties, restClientBuilder);
    }
}
