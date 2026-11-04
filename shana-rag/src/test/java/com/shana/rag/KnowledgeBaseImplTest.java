package com.shana.rag;

import com.shana.common.api.ErrorCode;
import com.shana.common.exception.BusinessException;
import com.shana.model.EmbeddingClient;
import com.shana.rag.store.RetrievedChunk;
import com.shana.rag.store.SaveOutcome;
import com.shana.rag.store.VectorStore;
import com.shana.rag.store.VectorizedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 知识库编排测试：存储与向量化都用替身。
 *
 * <p>重点验证四个决策：重复上传不重复花钱、并发写入不谎报切分数、
 * 没配嵌入模型时给出可读的报错、空查询不白调一次嵌入接口。
 */
class KnowledgeBaseImplTest {

    private VectorStore vectorStore;
    private EmbeddingClient embeddingClient;
    private KnowledgeBaseImpl knowledgeBase;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        embeddingClient = mock(EmbeddingClient.class);
        given(embeddingClient.embed(anyList())).willReturn(List.of(new float[]{0.1f, 0.2f}));
        knowledgeBase = new KnowledgeBaseImpl(vectorStore, provider(embeddingClient),
                new TextSplitter(100, 20, 10), 4, 0.35);
    }

    @Test
    @DisplayName("入库：切分后逐块向量化，片段序号从 0 连续递增")
    void shouldSplitVectorizeAndSave() {
        given(vectorStore.findDocumentIdByHash(anyString())).willReturn(Optional.empty());
        given(embeddingClient.embed(anyList()))
                .willReturn(List.of(new float[]{1f}, new float[]{2f}));
        given(vectorStore.saveDocument(eq("Flink 手册"), eq("docs/flink.md"), anyString(), anyList()))
                .willReturn(new SaveOutcome(7L, true));

        // 用小的 maxChars 保证切出两块：拆分逻辑由上一条测试覆盖，这里只关心编排
        KnowledgeBaseImpl small = new KnowledgeBaseImpl(vectorStore, provider(embeddingClient),
                new TextSplitter(20, 0, 0), 4, 0.35);
        IngestResult result = small.ingest(new IngestCommand("Flink 手册", "docs/flink.md",
                "第一句话。第二句话。第三句话。第四句话。第五句话。第六句话。"));

        assertThat(result).isEqualTo(new IngestResult(7L, 2, false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<VectorizedChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).saveDocument(eq("Flink 手册"), eq("docs/flink.md"), anyString(), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).extracting(chunk -> chunk.chunk().index()).containsExactly(0, 1);
        assertThat(captor.getValue().getFirst().vector()).containsExactly(1f);
    }

    @Test
    @DisplayName("重复内容直接返回已有文档，不再调用嵌入接口")
    void shouldSkipVectorizationForDuplicateContent() {
        given(vectorStore.findDocumentIdByHash(anyString())).willReturn(Optional.of(3L));

        IngestResult result = knowledgeBase.ingest(new IngestCommand("同一份资料", null, "内容不重要"));

        assertThat(result).isEqualTo(new IngestResult(3L, 0, true));
        // 这是最值钱的一条断言：内容重复时一次嵌入调用都不该发生
        verify(embeddingClient, never()).embed(anyList());
        verify(vectorStore, never()).saveDocument(anyString(), anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("并发写入时按已存在处理，不谎报本次写入了片段")
    void shouldHandleConcurrentInsert() {
        given(vectorStore.findDocumentIdByHash(anyString())).willReturn(Optional.empty());
        given(vectorStore.saveDocument(anyString(), any(), anyString(), anyList()))
                .willReturn(new SaveOutcome(9L, false));

        IngestResult result = knowledgeBase.ingest(new IngestCommand("标题", null, "正文内容"));

        assertThat(result).isEqualTo(new IngestResult(9L, 0, true));
    }

    @Test
    @DisplayName("没配嵌入模型时给出可读的报错，而不是 NPE")
    void shouldFailClearlyWithoutEmbeddingClient() {
        KnowledgeBaseImpl withoutEmbedding = new KnowledgeBaseImpl(vectorStore, provider(null),
                new TextSplitter(100, 20, 10), 4, 0.35);

        assertThatThrownBy(() -> withoutEmbedding.ingest(new IngestCommand("标题", null, "正文")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未配置向量化模型");
    }

    @Test
    @DisplayName("标题或正文为空时直接拒绝，不产生无意义的向量")
    void shouldRejectBlankInput() {
        assertThatThrownBy(() -> knowledgeBase.ingest(new IngestCommand(" ", null, "正文")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> knowledgeBase.ingest(new IngestCommand("标题", null, "")))
                .isInstanceOf(BusinessException.class);
        verify(embeddingClient, never()).embed(anyList());
    }

    @Test
    @DisplayName("检索：空查询不调用嵌入接口")
    void shouldSkipSearchForBlankQuery() {
        assertThat(knowledgeBase.search("   ", 4)).isEmpty();

        verify(embeddingClient, never()).embed(anyList());
        verify(vectorStore, never()).search(any(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("检索：把 topK 与阈值透传给存储层")
    void shouldDelegateSearchWithThreshold() {
        List<RetrievedChunk> hits = List.of(
                new RetrievedChunk(1L, 2L, "手册", "内容", 0.9));
        given(vectorStore.search(any(), anyInt(), anyDouble())).willReturn(hits);

        assertThat(knowledgeBase.search("checkpoint 怎么配", 3)).isEqualTo(hits);
        verify(vectorStore).search(any(), eq(3), eq(0.35));
    }

    @Test
    @DisplayName("检索：topK 传 0 时使用配置默认值")
    void shouldFallbackToDefaultTopK() {
        given(vectorStore.search(any(), anyInt(), anyDouble())).willReturn(List.of());

        knowledgeBase.search("问题", 0);

        verify(vectorStore).search(any(), eq(4), anyDouble());
    }

    @Test
    @DisplayName("内容指纹只取决于正文，同一内容换标题也算重复")
    void shouldHashContentOnly() {
        assertThat(KnowledgeBaseImpl.sha256("内容")).hasSize(64).isEqualTo(KnowledgeBaseImpl.sha256("内容"));
        assertThat(KnowledgeBaseImpl.sha256("内容")).isNotEqualTo(KnowledgeBaseImpl.sha256("内容 "));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(bean);
        return provider;
    }
}
