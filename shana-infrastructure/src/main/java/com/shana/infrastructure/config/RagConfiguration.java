package com.shana.infrastructure.config;

import com.shana.model.EmbeddingClient;
import com.shana.rag.KnowledgeBase;
import com.shana.rag.KnowledgeBaseImpl;
import com.shana.rag.TextSplitter;
import com.shana.rag.store.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库装配。
 *
 * <p>装配条件与 {@code PgVectorStore} 保持一致（{@code store=jdbc}），而不是用
 * {@code @ConditionalOnBean(VectorStore.class)}：后者在普通 {@code @Configuration} 中依赖
 * 组件扫描顺序，Spring Boot 官方只保证它在自动配置类里可靠。用同一个配置条件，
 * "什么时候有知识库"这件事只取决于一处配置，不会出现两个条件判断漂移。
 *
 * <p>{@link EmbeddingClient} 仍然用 {@link ObjectProvider} 传入：它是"缺了也能启动"的可选依赖，
 * 而 {@link VectorStore} 是"装了这个 Bean 就一定存在"的必需依赖，两者容错级别不同。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "shana.memory", name = "store", havingValue = "jdbc", matchIfMissing = true)
public class RagConfiguration {

    @Bean
    TextSplitter textSplitter(@Value("${shana.rag.chunk.max-chars:800}") int maxChars,
                              @Value("${shana.rag.chunk.overlap-chars:120}") int overlapChars,
                              @Value("${shana.rag.chunk.min-chars:50}") int minChars) {
        return new TextSplitter(maxChars, overlapChars, minChars);
    }

    @Bean
    KnowledgeBase knowledgeBase(VectorStore vectorStore,
                                ObjectProvider<EmbeddingClient> embeddingClients,
                                TextSplitter textSplitter,
                                @Value("${shana.rag.top-k:4}") int topK,
                                @Value("${shana.rag.min-score:0.35}") double minScore) {
        return new KnowledgeBaseImpl(vectorStore, embeddingClients, textSplitter, topK, minScore);
    }
}
