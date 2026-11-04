package com.shana.application;

import com.shana.common.exception.BusinessException;
import com.shana.rag.KnowledgeBase;
import com.shana.rag.TextSplitter;
import com.shana.rag.store.VectorStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配验证：{@code store=jdbc} 时知识库应当在场，且切片参数来自配置。
 *
 * <p>为什么需要这个测试：{@code RagConfiguration} 与 {@code PgVectorStore} 用了同一个
 * 配置条件，两处一旦写得不一致，就会出现"有存储没服务"或"有服务没存储"。
 * 这类问题单元测试看不见，只有在完整上下文里才能发现。
 *
 * <p>本测试不需要真实数据库：DataSource 是懒建连的，Flyway 在 test profile 下关闭，
 * 所以只验证装配，不验证 SQL。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "shana.memory.store=jdbc")
class RagWiringTest {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private KnowledgeBase knowledgeBase;

    @Autowired
    private TextSplitter textSplitter;

    @Test
    @DisplayName("store=jdbc 时装配 pgvector 存储与知识库服务")
    void shouldWireKnowledgeBaseForJdbcStore() {
        assertThat(vectorStore).isNotNull();
        assertThat(knowledgeBase).isNotNull();
        assertThat(textSplitter).isNotNull();
    }

    @Test
    @DisplayName("没有配置嵌入模型时，检索给出可读报错而不是 NPE")
    void shouldExplainMissingEmbeddingConfiguration() {
        // 测试环境没有 shana.embedding.api-key，适配器不会装配。
        // 此时知识库 Bean 仍然存在（入库/检索接口能进到业务逻辑里），
        // 但调用时会明确告诉使用者缺哪个配置——这比容器启动失败或空指针好得多
        assertThatThrownBy(() -> knowledgeBase.search("Flink checkpoint", 4))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未配置向量化模型");

        assertThatThrownBy(() -> knowledgeBase.ingest(
                new com.shana.rag.IngestCommand("标题", null, "正文内容")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未配置向量化模型");
    }
}
