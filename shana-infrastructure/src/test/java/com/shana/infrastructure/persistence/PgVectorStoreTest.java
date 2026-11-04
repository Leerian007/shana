package com.shana.infrastructure.persistence;

import com.shana.common.exception.BusinessException;
import com.shana.infrastructure.persistence.entity.ChunkSearchRow;
import com.shana.infrastructure.persistence.entity.RagDocumentEntity;
import com.shana.infrastructure.persistence.mapper.RagChunkMapper;
import com.shana.infrastructure.persistence.mapper.RagDocumentMapper;
import com.shana.rag.DocumentChunk;
import com.shana.rag.store.RetrievedChunk;
import com.shana.rag.store.SaveOutcome;
import com.shana.rag.store.VectorizedChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * pgvector 存储实现测试：Mapper 用替身，验证"调用了什么 SQL 语义"。
 *
 * <p>本机没有 PostgreSQL，所以真实 SQL 行为（HNSW 是否走索引、向量距离是否算对）
 * 只能靠手动联调覆盖。这里守的是更容易出错的部分：并发写入的处理与向量格式。
 */
class PgVectorStoreTest {

    private RagDocumentMapper documentMapper;
    private RagChunkMapper chunkMapper;
    private PgVectorStore store;

    @BeforeEach
    void setUp() {
        documentMapper = mock(RagDocumentMapper.class);
        chunkMapper = mock(RagChunkMapper.class);
        store = new PgVectorStore(documentMapper, chunkMapper);
    }

    @Test
    @DisplayName("新建文档：先插文档再逐块插向量，片段序号与向量一一对应")
    void shouldInsertDocumentThenChunks() {
        given(documentMapper.insertIgnoreConflict(any())).willReturn(1);
        given(documentMapper.selectIdByHash("hash-1")).willReturn(7L);

        SaveOutcome outcome = store.saveDocument("手册", "docs/a.md", "hash-1", List.of(
                new VectorizedChunk(new DocumentChunk(0, "第一段"), new float[]{1f, 2f}),
                new VectorizedChunk(new DocumentChunk(1, "第二段"), new float[]{3f, 4f})));

        assertThat(outcome).isEqualTo(new SaveOutcome(7L, true));
        verify(chunkMapper).insertVector(7L, 0, "第一段", "[1.0,2.0]");
        verify(chunkMapper).insertVector(7L, 1, "第二段", "[3.0,4.0]");
    }

    @Test
    @DisplayName("文档行带上切分后的片段数，便于不查 chunk 表也知道规模")
    void shouldStoreChunkCount() {
        given(documentMapper.insertIgnoreConflict(any())).willReturn(1);
        given(documentMapper.selectIdByHash(anyString())).willReturn(1L);

        store.saveDocument("手册", null, "hash", List.of(
                new VectorizedChunk(new DocumentChunk(0, "只有一段"), new float[]{1f})));

        verify(documentMapper).insertIgnoreConflict(org.mockito.ArgumentMatchers.argThat(
                (RagDocumentEntity document) -> document.getChunkCount() == 1
                        && "hash".equals(document.getContentHash())));
    }

    @Test
    @DisplayName("并发写入命中唯一约束时不写片段，返回已存在的文档")
    void shouldSkipChunksWhenDocumentAlreadyExists() {
        given(documentMapper.insertIgnoreConflict(any())).willReturn(0);
        given(documentMapper.selectIdByHash("hash-1")).willReturn(9L);

        SaveOutcome outcome = store.saveDocument("手册", null, "hash-1", List.of(
                new VectorizedChunk(new DocumentChunk(0, "第一段"), new float[]{1f})));

        assertThat(outcome).isEqualTo(new SaveOutcome(9L, false));
        verify(chunkMapper, never()).insertVector(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("插入后查不到文档 ID 时直接失败，而不是返回 0 让上层写出脏数据")
    void shouldFailWhenDocumentIdMissing() {
        given(documentMapper.insertIgnoreConflict(any())).willReturn(1);
        given(documentMapper.selectIdByHash(anyString())).willReturn(null);

        assertThatThrownBy(() -> store.saveDocument("手册", null, "hash", List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("查询不到记录");
    }

    @Test
    @DisplayName("检索结果映射成领域对象，向量本身不出库")
    void shouldMapSearchRows() {
        ChunkSearchRow row = new ChunkSearchRow();
        row.setChunkId(11L);
        row.setDocumentId(7L);
        row.setDocumentTitle("Flink 手册");
        row.setContent("checkpoint 依赖 state backend");
        row.setScore(0.87);
        given(chunkMapper.searchByVector(anyString(), anyInt(), anyDouble())).willReturn(List.of(row));

        List<RetrievedChunk> hits = store.search(new float[]{1f, 2f}, 4, 0.35);

        assertThat(hits).containsExactly(
                new RetrievedChunk(11L, 7L, "Flink 手册", "checkpoint 依赖 state backend", 0.87));
        verify(chunkMapper).searchByVector("[1.0,2.0]", 4, 0.35);
    }

    @Test
    @DisplayName("空向量或非法 topK 时不查库")
    void shouldSkipSearchForInvalidArguments() {
        assertThat(store.search(new float[0], 4, 0.35)).isEmpty();
        assertThat(store.search(null, 4, 0.35)).isEmpty();
        assertThat(store.search(new float[]{1f}, 0, 0.35)).isEmpty();

        verify(chunkMapper, never()).searchByVector(anyString(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("删除文档返回是否真的删到了行")
    void shouldReportDeleteResult() {
        given(documentMapper.deleteById(7L)).willReturn(1);
        given(documentMapper.deleteById(8L)).willReturn(0);

        assertThat(store.deleteDocument(7L)).isTrue();
        assertThat(store.deleteDocument(8L)).isFalse();
    }

    @Test
    @DisplayName("查重按内容指纹透传到 Mapper")
    void shouldFindDocumentByHash() {
        given(documentMapper.selectIdByHash(eq("hash-1"))).willReturn(3L);
        // MyBatis 查不到时返回 null（Mockito 对 Long 的默认返回值是 0L，必须显式声明）
        given(documentMapper.selectIdByHash(eq("hash-2"))).willReturn(null);

        assertThat(store.findDocumentIdByHash("hash-1")).contains(3L);
        assertThat(store.findDocumentIdByHash("hash-2")).isEmpty();
    }
}
